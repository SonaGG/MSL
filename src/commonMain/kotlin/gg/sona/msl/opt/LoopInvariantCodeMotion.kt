package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.ControlFlowGraph

object LoopInvariantCodeMotion {
    fun run(function: IrFunction): Boolean {
        var changed = false
        val cfg = ControlFlowGraph(function)
        for (header in cfg.reversePostorder.filter { it.construct == ConstructKind.Loop }) {
            val body = loopBlocks(header, cfg) ?: continue
            val outside = cfg.predecessorsOf(header).filter { it !in body }
            val preheader = outside.singleOrNull() ?: continue
            val terminator = preheader.terminator ?: continue
            if (terminator.opcode != Opcode.Branch) continue
            val hoisted = HashSet<Instruction>()
            for (block in cfg.reversePostorder) {
                if (block !in body) continue
                for (instruction in block.instructions.toList()) {
                    if (instruction.opcode == Opcode.Phi || instruction.isTerminator) continue
                    if (!hoistable(instruction)) continue
                    val invariant = instruction.operands.all { operand -> operand !is Instruction || operand.block !in body || operand in hoisted }
                    if (!invariant) continue
                    block.instructions.remove(instruction)
                    instruction.block = preheader
                    preheader.instructions.add(preheader.instructions.size - 1, instruction)
                    hoisted.add(instruction)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun hoistable(instruction: Instruction): Boolean {
        if (Purity.isSpeculatable(instruction)) return true
        if (instruction.opcode != Opcode.Load) return false
        val storage = Purity.rootStorage(instruction.operands[0])
        if (storage != StorageClass.Uniform && storage != StorageClass.PushConstant && storage != StorageClass.Input && storage != StorageClass.Private) return false
        if (storage == StorageClass.Private && !Purity.isReadOnly(instruction.operands[0])) return false
        var pointer: Value = instruction.operands[0]
        while (pointer is Instruction) {
            if (pointer.opcode != Opcode.AccessChain) return false
            if (pointer.operands.drop(1).any { it !is ConstantScalar }) return false
            pointer = pointer.operands[0]
        }
        return true
    }

    fun loopBlocks(header: Block, cfg: ControlFlowGraph): Set<Block>? {
        val latch = header.continueTarget ?: return null
        val body = HashSet<Block>()
        body.add(header)
        val work = ArrayDeque<Block>()
        if (body.add(latch)) work.add(latch)
        while (work.isNotEmpty()) {
            val block = work.removeFirst()
            for (predecessor in cfg.predecessorsOf(block)) {
                if (predecessor !in cfg.reachable) continue
                if (body.add(predecessor)) work.add(predecessor)
            }
        }
        if (header.merge in body) return null
        return body
    }
}
