package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Undef

object UnreachableBlockElimination {
    fun run(function: IrFunction) {
        while (true) {
            val reachable = ControlFlowGraph(function).reachable
            val headersOfContinue = HashMap<Block, Block>()
            val requiredMerges = HashSet<Block>()
            for (block in function.blocks) {
                if (block !in reachable) continue
                block.merge?.let { requiredMerges.add(it) }
                block.continueTarget?.let {
                    requiredMerges.add(it)
                    headersOfContinue[it] = block
                }
            }
            var changed = false
            val iterator = function.blocks.iterator()
            while (iterator.hasNext()) {
                val block = iterator.next()
                if (block in reachable) continue
                if (block in requiredMerges) {
                    if (neutralize(block, headersOfContinue[block])) changed = true
                    continue
                }
                iterator.remove()
                changed = true
            }
            pruneIncoming(function)
            if (!changed) return
        }
    }

    fun pruneIncoming(function: IrFunction) {
        val predecessors = HashMap<Block, MutableSet<Block>>()
        for (block in function.blocks) {
            for (successor in block.successors) predecessors.getOrPut(successor) { HashSet() }.add(block)
        }
        for (block in function.blocks) {
            val actual = predecessors[block] ?: emptySet()
            for (phi in block.phis) {
                val seen = HashSet<Block>()
                for (i in phi.targets.indices.reversed()) {
                    val target = phi.targets[i]
                    if (target !in actual || !seen.add(target)) {
                        phi.targets.removeAt(i)
                        phi.operands.removeAt(i)
                    }
                }
            }
        }
    }

    private fun neutralize(block: Block, loopHeader: Block?): Boolean {
        val terminator = block.terminator
        val alreadyNeutral = block.instructions.size == 1 && block.construct == ConstructKind.None && when {
            loopHeader != null -> terminator?.opcode == Opcode.Branch && terminator.targets.single() === loopHeader
            else -> terminator?.opcode == Opcode.Unreachable
        }
        if (alreadyNeutral) return false
        block.instructions.clear()
        block.clearConstruct()
        val instruction = if (loopHeader != null) {
            Instruction(Opcode.Branch, IrVoid).also { it.targets.add(loopHeader) }
        } else {
            Instruction(Opcode.Unreachable, IrVoid)
        }
        instruction.block = block
        block.instructions.add(instruction)
        if (loopHeader != null) {
            for (phi in loopHeader.phis) {
                if (block !in phi.targets) {
                    phi.operands.add(Undef(phi.type))
                    phi.targets.add(block)
                }
            }
        }
        return true
    }
}
