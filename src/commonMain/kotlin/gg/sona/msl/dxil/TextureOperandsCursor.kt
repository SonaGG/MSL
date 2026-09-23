package gg.sona.msl.dxil

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.ir.Value

class TextureOperandsCursor(private val instruction: Instruction) {
    val mask = TextureOperands(instruction.literals[0])
    val extra = instruction.literals.getOrElse(1) { 0 }
    private var index = 1

    fun next(): Value = instruction.operands[index++]

    fun take(flag: TextureOperands): Value? = if (flag in mask) next() else null
}
