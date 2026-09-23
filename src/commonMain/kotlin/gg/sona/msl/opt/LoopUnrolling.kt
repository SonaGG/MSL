package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.ControlFlowGraph

class LoopUnrolling(private val maxIterations: Int, private val maxInstructions: Int) {
    private var counter = 0

    fun run(function: IrFunction): Boolean {
        var changed = false
        while (true) {
            val cfg = ControlFlowGraph(function)
            val loop = cfg.reversePostorder.filter { it.construct == ConstructKind.Loop }.firstNotNullOfOrNull { analyze(it, cfg, function) } ?: return changed
            unroll(function, loop)
            changed = true
        }
    }

    private fun analyze(header: Block, cfg: ControlFlowGraph, function: IrFunction): LoopShape? {
        val merge = header.merge ?: return null
        val latch = header.continueTarget ?: return null
        val blocks = LoopInvariantCodeMotion.loopBlocks(header, cfg) ?: return null
        val preheader = cfg.predecessorsOf(header).filter { it !in blocks }.singleOrNull() ?: return null
        if (cfg.predecessorsOf(header).toSet() != setOf(preheader, latch)) return null
        val headerBranch = header.terminator ?: return null
        if (headerBranch.opcode != Opcode.Branch) return null
        val condition = headerBranch.targets[0]
        if (condition === latch || condition.construct != ConstructKind.None) return null
        if (cfg.predecessorsOf(condition) != listOf(header)) return null
        val test = condition.terminator ?: return null
        if (test.opcode != Opcode.CondBranch) return null
        val exitsOnTrue = test.targets[0] === merge
        val body = if (exitsOnTrue) test.targets[1] else test.targets[0]
        if (test.targets[if (exitsOnTrue) 0 else 1] !== merge || body === merge) return null
        if (cfg.predecessorsOf(merge) != listOf(condition)) return null
        val latchBranch = latch.terminator ?: return null
        if (latchBranch.opcode != Opcode.Branch || latchBranch.targets[0] !== header) return null
        if (latch.construct != ConstructKind.None || latch.phis.isNotEmpty()) return null
        for (block in blocks) {
            if (block === header || block === condition) continue
            for (successor in block.successors) if (successor !in blocks) return null
            if (block !== latch && block.continueTarget === latch) return null
        }
        if (latch !== body && cfg.predecessorsOf(latch).size != 1) return null
        val trips = tripCount(header, condition, test, exitsOnTrue, preheader, latch) ?: return null
        val size = blocks.sumOf { block -> block.instructions.count { it.opcode != Opcode.Phi && !it.isTerminator } }
        if (trips > maxIterations || trips.toLong() * size > maxInstructions) return null
        if (function.blocks.none { it === header }) return null
        return LoopShape(header, condition, body, latch, merge, preheader, blocks, trips)
    }

    private fun tripCount(header: Block, condition: Block, test: Instruction, exitsOnTrue: Boolean, preheader: Block, latch: Block): Int? {
        val compare = test.operands[0] as? Instruction ?: return null
        if (compare.block !== condition || compare.operands.size != 2) return null
        val phiIndex = compare.operands.indexOfFirst { it is Instruction && it.opcode == Opcode.Phi && it.block === header }
        if (phiIndex < 0) return null
        val phi = compare.operands[phiIndex] as Instruction
        val bound = compare.operands[1 - phiIndex] as? ConstantScalar ?: return null
        val type = phi.type as? IrInt ?: return null
        val initial = phi.operands[phi.targets.indexOf(preheader)] as? ConstantScalar ?: return null
        val next = phi.operands[phi.targets.indexOf(latch)] as? Instruction ?: return null
        if (next.opcode != Opcode.IAdd && next.opcode != Opcode.ISub) return null
        val stepIndex = next.operands.indexOfFirst { it is ConstantScalar }
        if (stepIndex < 0 || next.operands[1 - stepIndex] !== phi) return null
        if (next.opcode == Opcode.ISub && stepIndex != 1) return null
        val step = (next.operands[stepIndex] as ConstantScalar).bits * (if (next.opcode == Opcode.ISub) -1 else 1)
        var value = initial
        var trips = 0
        while (true) {
            val operands: List<Value> = if (phiIndex == 0) listOf(value, bound) else listOf(bound, value)
            val result = ConstantFolding.fold(compare, operands) as? ConstantScalar ?: return null
            val continues = result.asBoolean != exitsOnTrue
            if (!continues) return trips
            if (++trips > maxIterations) return null
            value = ConstantScalar.int(type, value.bits + step)
        }
    }

    private fun unroll(function: IrFunction, loop: LoopShape) {
        val id = counter++
        val headerPhis = loop.header.phis
        var phiValues: Map<Instruction, Value> = headerPhis.associateWith { it.operands[it.targets.indexOf(loop.preheader)] }
        val order = function.blocks.filter { it in loop.blocks && it !== loop.header && it !== loop.condition }
        val created = ArrayList<Block>()
        var previousExit: Block = loop.preheader
        var previousTarget: Block = loop.header
        fun head(iteration: String, mapping: HashMap<Value, Value>): Block {
            val block = Block("unrolled$id.$iteration.head")
            block.function = function
            for (source in listOf(loop.header, loop.condition)) {
                for (instruction in source.instructions) {
                    if (instruction.opcode == Opcode.Phi || instruction.isTerminator) continue
                    block.append(clone(instruction, mapping, emptyMap()))
                }
            }
            created.add(block)
            return block
        }

        fun redirect(from: Block, oldTarget: Block, newTarget: Block) {
            from.terminator!!.replaceTarget(oldTarget, newTarget)
        }

        for (iteration in 0 until loop.trips) {
            val mapping = HashMap<Value, Value>(phiValues)
            val start = head(iteration.toString(), mapping)
            redirect(previousExit, previousTarget, start)
            val blockMapping = HashMap<Block, Block>()
            for (source in order) {
                val clone = Block("unrolled$id.$iteration.${source.name}")
                clone.function = function
                blockMapping[source] = clone
            }
            for (source in order) {
                val clone = blockMapping.getValue(source)
                clone.construct = source.construct
                clone.merge = source.merge?.let { blockMapping[it] }
                clone.continueTarget = source.continueTarget?.let { blockMapping[it] }
                for (instruction in source.instructions) clone.instructions.add(clone(instruction, mapping, blockMapping).also { it.block = clone })
            }
            for (source in order) for (instruction in blockMapping.getValue(source).instructions) instruction.replaceOperands(mapping)
            start.append(Instruction(Opcode.Branch, IrVoid).also { it.targets.add(blockMapping.getValue(loop.body)) })
            created.addAll(order.map { blockMapping.getValue(it) })
            val latch = blockMapping.getValue(loop.latch)
            phiValues = headerPhis.associateWith { phi -> IrRewriter.resolve(phi.operands[phi.targets.indexOf(loop.latch)], mapping) }
            previousExit = latch
            previousTarget = loop.header
        }
        val finalMapping = HashMap<Value, Value>(phiValues)
        val exit = head("exit", finalMapping)
        exit.append(Instruction(Opcode.Branch, IrVoid).also { it.targets.add(loop.merge) })
        redirect(previousExit, previousTarget, exit)
        for (phi in loop.merge.phis) phi.replaceTarget(loop.condition, exit)
        val index = function.blocks.indexOf(loop.header)
        function.blocks.removeAll { it in loop.blocks }
        for (block in created) block.function = function
        function.blocks.addAll(index.coerceAtMost(function.blocks.size), created)
        IrRewriter.replace(function, finalMapping)
    }

    private fun clone(instruction: Instruction, mapping: HashMap<Value, Value>, blocks: Map<Block, Block>): Instruction {
        val copy = Instruction(instruction.opcode, instruction.type, instruction.operands.map { IrRewriter.resolve(it, mapping) })
        copy.literals = instruction.literals
        copy.intrinsic = instruction.intrinsic
        copy.callee = instruction.callee
        copy.targets.addAll(instruction.targets.map { blocks[it] ?: it })
        mapping[instruction] = copy
        return copy
    }
}
