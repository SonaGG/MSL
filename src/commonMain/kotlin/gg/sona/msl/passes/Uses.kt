package gg.sona.msl.passes

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Value

class Uses(function: IrFunction) {
    private val users = HashMap<Value, MutableList<Instruction>>()

    init {
        for (instruction in function.instructions()) {
            for (operand in instruction.operands) users.getOrPut(operand) { ArrayList() }.add(instruction)
        }
    }

    fun of(value: Value): List<Instruction> = users[value]?.toList() ?: emptyList()

    fun isUsed(value: Value): Boolean = users[value]?.isNotEmpty() == true
}
