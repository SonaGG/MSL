package gg.sona.msl.passes

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import kotlin.math.PI
import kotlin.math.ln

class IntrinsicExpansion(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    fun run(function: IrFunction) {
        var rounds = 0
        while (rounds++ < 16) {
            val replacements = HashMap<Value, Value>()
            for (block in function.blocks.toList()) {
                for (instruction in block.instructions.toList()) {
                    if (instruction.opcode != Opcode.Intrinsic) continue
                    val intrinsic = instruction.intrinsic!!
                    if (isNative(intrinsic, instruction)) continue
                    val builder = IrBuilder(function)
                    builder.positionBefore(instruction)
                    val replacement = IntrinsicExpander(builder, instruction).expand() ?: continue
                    replacements[instruction] = replacement
                    block.instructions.remove(instruction)
                }
            }
            if (replacements.isEmpty()) return
            for (instruction in function.instructions()) instruction.replaceOperands(replacements)
        }
    }
}
