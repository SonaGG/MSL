package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.passes.Uses

object Scheduling {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        var changed = false
        for (block in function.blocks) changed = schedule(block, uses) or changed
        return changed
    }

    private fun schedule(block: Block, uses: Uses): Boolean {
        val instructions = block.instructions
        var changed = false
        var index = instructions.size - 1
        while (index >= 0) {
            val instruction = instructions[index]
            index--
            if (!movable(instruction)) continue
            val users = uses.of(instruction)
            if (users.isEmpty() || users.any { it.block !== block || it.opcode == Opcode.Phi }) continue
            val first = users.minOf { instructions.indexOf(it) }
            val current = instructions.indexOf(instruction)
            if (first <= current + 1) continue
            instructions.removeAt(current)
            instructions.add(first - 1, instruction)
            changed = true
        }
        return changed
    }

    private fun movable(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Load -> Purity.isReadOnly(instruction.operands[0])
        Opcode.Phi, Opcode.Variable, Opcode.Store, Opcode.Call -> false
        Opcode.AccessChain -> true
        else -> !instruction.isTerminator && Purity.isFoldable(instruction)
    }
}
