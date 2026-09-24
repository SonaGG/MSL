package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.ControlFlowGraph
import gg.sona.msl.passes.Uniformity
import gg.sona.msl.passes.Uses

class LoopUnswitching(private val maxInstructions: Int) {
    private var counter = 0

    private class Candidate(
        val header: Block,
        val body: Set<Block>,
        val preheader: Block,
        val exiting: Block,
        val selection: Block,
    )

    fun run(function: IrFunction): Boolean {
        val cfg = ControlFlowGraph(function)
        val uniformity = Uniformity(function)
        val candidate = cfg.reversePostorder.firstNotNullOfOrNull { analyze(it, cfg, uniformity) } ?: return false
        unswitch(function, candidate)
        return true
    }

    private fun analyze(header: Block, cfg: ControlFlowGraph, uniformity: Uniformity): Candidate? {
        if (header.construct != ConstructKind.Loop) return null
        val merge = header.merge ?: return null
        val body = LoopInvariantCodeMotion.loopBlocks(header, cfg) ?: return null
        if (body.sumOf { it.instructions.size } > maxInstructions) return null
        val preheader = cfg.predecessorsOf(header).filter { it !in body }.singleOrNull() ?: return null
        val entry = preheader.terminator ?: return null
        if (entry.opcode != Opcode.Branch || preheader.construct != ConstructKind.None || preheader.merge != null) return null
        val exits = body.flatMap { block -> block.successors.distinct().filter { it !in body }.map { block to it } }
        val (exiting, target) = exits.singleOrNull() ?: return null
        if (target !== merge) return null
        val selection = body.firstOrNull { block ->
            val terminator = block.terminator
            block.construct == ConstructKind.Selection && block !== header && block !== exiting && block !== header.continueTarget &&
                terminator != null && terminator.opcode == Opcode.CondBranch && invariant(terminator.operands[0], body) &&
                uniformity.isUniform(terminator.operands[0])
        } ?: return null
        return Candidate(header, body, preheader, exiting, selection)
    }

    private fun invariant(value: Value, body: Set<Block>): Boolean = value !is Instruction || value.block !in body

    private fun unswitch(function: IrFunction, loop: Candidate) {
        val id = counter++
        val header = loop.header
        val merge = header.merge!!
        val uses = Uses(function)
        val order = function.blocks.filter { it in loop.body }
        val blocks = HashMap<Block, Block>()
        val values = HashMap<Value, Value>()
        for (source in order) blocks[source] = newBlock(function, "unswitched$id.${source.name}")
        for (source in order) {
            val clone = blocks.getValue(source)
            clone.construct = source.construct
            clone.merge = source.merge?.let { blocks[it] ?: it }
            clone.continueTarget = source.continueTarget?.let { blocks[it] ?: it }
            for (instruction in source.instructions) {
                val copy = Instruction(instruction.opcode, instruction.type, instruction.operands.toList())
                copy.literals = instruction.literals
                copy.intrinsic = instruction.intrinsic
                copy.callee = instruction.callee
                copy.targets.addAll(instruction.targets.map { blocks[it] ?: it })
                copy.block = clone
                clone.instructions.add(copy)
                values[instruction] = copy
            }
        }
        for (source in order) for (instruction in blocks.getValue(source).instructions) instruction.replaceOperands(values)

        val firstMerge = newBlock(function, "unswitched$id.merge.true")
        val secondMerge = newBlock(function, "unswitched$id.merge.false")
        val join = newBlock(function, "unswitched$id.join")
        loop.exiting.terminator!!.replaceTarget(merge, firstMerge)
        blocks.getValue(loop.exiting).terminator!!.replaceTarget(merge, secondMerge)
        header.merge = firstMerge
        blocks.getValue(header).merge = secondMerge
        firstMerge.append(branch(join))
        secondMerge.append(branch(join))

        val replacements = HashMap<Value, Value>()
        for (block in order) {
            for (instruction in block.instructions) {
                val outside = uses.of(instruction).filter { it.block !in loop.body }
                if (outside.isEmpty() || instruction.type == IrVoid) continue
                replacements[instruction] = phi(join, instruction, firstMerge, values.getValue(instruction), secondMerge)
            }
        }
        for (phi in merge.phis) {
            val index = phi.targets.indexOf(loop.exiting)
            if (index < 0) continue
            val value = phi.operands[index]
            val cloned = values[value] ?: value
            phi.targets[index] = join
            phi.operands[index] = if (cloned === value) value else phi(join, value, firstMerge, cloned, secondMerge)
        }
        join.append(branch(merge))
        for (block in function.blocks) {
            if (block in loop.body || block in blocks.values || block === join) continue
            for (instruction in block.instructions) {
                for (i in instruction.operands.indices) replacements[instruction.operands[i]]?.let { instruction.operands[i] = it }
            }
        }

        val condition = loop.selection.terminator!!.operands[0]
        fold(loop.selection, true)
        fold(blocks.getValue(loop.selection), false)
        loop.preheader.instructions.remove(loop.preheader.terminator!!)
        val select = Instruction(Opcode.CondBranch, IrVoid, listOf(condition))
        select.targets.add(header)
        select.targets.add(blocks.getValue(header))
        loop.preheader.append(select)
        loop.preheader.construct = ConstructKind.Selection
        loop.preheader.merge = join
        val index = function.blocks.indexOf(order.last()) + 1
        function.blocks.removeAll { it in blocks.values || it === firstMerge || it === secondMerge || it === join }
        function.blocks.addAll(index, listOf(firstMerge) + order.map { blocks.getValue(it) } + listOf(secondMerge, join))
    }

    private fun fold(selection: Block, taken: Boolean) {
        val terminator = selection.terminator!!
        val target = terminator.targets[if (taken) 0 else 1]
        selection.instructions.remove(terminator)
        selection.append(branch(target))
        selection.construct = ConstructKind.None
        selection.merge = null
    }

    private fun branch(target: Block): Instruction = Instruction(Opcode.Branch, IrVoid).also { it.targets.add(target) }

    private fun phi(block: Block, first: Value, firstBlock: Block, second: Value, secondBlock: Block): Instruction {
        val phi = Instruction(Opcode.Phi, first.type, listOf(first, second))
        phi.targets.add(firstBlock)
        phi.targets.add(secondBlock)
        phi.block = block
        block.instructions.add(block.phis.size, phi)
        return phi
    }

    private fun newBlock(function: IrFunction, name: String): Block = Block(name).also { it.function = function }
}
