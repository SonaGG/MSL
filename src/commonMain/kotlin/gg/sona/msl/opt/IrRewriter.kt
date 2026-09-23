package gg.sona.msl.opt

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Value

object IrRewriter {
    fun replace(function: IrFunction, replacements: Map<Value, Value>) {
        if (replacements.isEmpty()) return
        for (block in function.blocks) {
            block.instructions.removeAll { it in replacements }
            for (instruction in block.instructions) {
                for (i in instruction.operands.indices) instruction.operands[i] = resolve(instruction.operands[i], replacements)
            }
        }
    }

    fun resolve(value: Value, replacements: Map<Value, Value>): Value {
        var current = value
        var steps = 0
        while (true) {
            val next = replacements[current] ?: return current
            if (next === current || ++steps > 1000) return current
            current = next
        }
    }

    fun insertBefore(anchor: Instruction, instruction: Instruction): Instruction {
        val block = anchor.block!!
        instruction.block = block
        block.instructions.add(block.instructions.indexOf(anchor), instruction)
        return instruction
    }
}
