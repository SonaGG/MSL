package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.passes.Uses

object Scheduling {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        var changed = false
        for (block in function.blocks) changed = schedule(block, uses) or changed
        return changed
    }

    private fun ordered(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Load -> !Purity.isReadOnly(instruction.operands[0])
        Opcode.Store, Opcode.Call, Opcode.Variable -> true
        Opcode.Intrinsic -> !Purity.isRepeatable(instruction)
        else -> false
    }

    private fun schedule(block: Block, uses: Uses): Boolean {
        val all = block.instructions
        val phis = all.takeWhile { it.opcode == Opcode.Phi }
        val terminator = all.last().takeIf { it.isTerminator } ?: return false
        val body = all.subList(phis.size, all.size - 1).toList()
        if (body.size < 2) return false
        val position = body.withIndex().associate { it.value to it.index }
        val dependencies = HashMap<Instruction, MutableSet<Instruction>>()
        var previousOrdered: Instruction? = null
        for (instruction in body) {
            val set = HashSet<Instruction>()
            for (operand in instruction.operands) if (operand is Instruction && operand in position) set.add(operand)
            if (ordered(instruction)) {
                previousOrdered?.let(set::add)
                previousOrdered = instruction
            } else if (instruction.opcode == Opcode.Load && previousOrdered != null && !Purity.isReadOnly(instruction.operands[0])) {
                set.add(previousOrdered)
            }
            dependencies[instruction] = set
        }
        val remainingUses = HashMap<Instruction, Int>()
        for (instruction in body) {
            remainingUses[instruction] = uses.of(instruction).count { it.block === block && it in position }
        }
        val scheduled = LinkedHashSet<Instruction>()
        val result = ArrayList<Instruction>(body.size)
        while (result.size < body.size) {
            var best: Instruction? = null
            var bestScore = Int.MAX_VALUE
            for (instruction in body) {
                if (instruction in scheduled || dependencies.getValue(instruction).any { it !in scheduled }) continue
                val defines = if (instruction.type == IrVoid || uses.of(instruction).none { it.block === block }) 0 else 1
                val kills = instruction.operands.distinct().count { it is Instruction && it in position && remainingUses[it] == 1 }
                val score = (defines - kills) * WEIGHT + position.getValue(instruction)
                if (score < bestScore) {
                    bestScore = score
                    best = instruction
                }
            }
            val chosen = best!!
            scheduled.add(chosen)
            result.add(chosen)
            for (operand in chosen.operands.distinct()) if (operand is Instruction && operand in position) remainingUses[operand] = remainingUses.getValue(operand) - 1
        }
        if (result == body) return false
        all.clear()
        all.addAll(phis)
        all.addAll(result)
        all.add(terminator)
        return true
    }

    private const val WEIGHT = 1_000_000
}
