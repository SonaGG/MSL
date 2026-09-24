package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

class StrengthReduction(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (block in function.blocks) {
            for (instruction in block.instructions.toList()) {
                builder.positionBefore(instruction)
                val replacement = when (instruction.opcode) {
                    Opcode.FDiv -> reciprocal(builder, instruction, uses)
                    Opcode.IMul -> multiply(builder, instruction)
                    else -> null
                }
                if (replacement != null) replacements[instruction] = replacement
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun reciprocal(builder: IrBuilder, instruction: Instruction, uses: Uses): Value? {
        val (dividend, divisor) = instruction.operands
        if (divisor is IrConstant || isOne(dividend)) return null
        if ((instruction.type.scalar as? IrFloat)?.bits == 64) return null
        val divisions = uses.of(divisor).count { it.opcode == Opcode.FDiv && it.operands[1] === divisor && it.type == instruction.type }
        if (divisions < 2) return null
        val inverse = builder.binary(Opcode.FDiv, instruction.type, Constants.splat(instruction.type, Constants.one(instruction.type.scalar)), divisor)
        return builder.binary(Opcode.FMul, instruction.type, dividend, inverse)
    }

    private fun isOne(value: Value): Boolean = value is IrConstant && Constants.scalars(value).all { it == Constants.one(it.type.scalar) }

    private fun multiply(builder: IrBuilder, instruction: Instruction): Value? {
        val type = instruction.type as? IrInt ?: return null
        val constantIndex = instruction.operands.indexOfFirst { it is ConstantScalar }
        if (constantIndex < 0) return null
        val factor = (instruction.operands[constantIndex] as ConstantScalar).bits
        val x = instruction.operands[1 - constantIndex]
        val mask = if (type.bits == 64) -1L else (1L shl type.bits) - 1
        val value = factor and mask
        if (value <= 2) return null
        val high = 63 - value.countLeadingZeroBits()
        fun shifted(amount: Int): Value = builder.binary(Opcode.Shl, type, x, ConstantScalar.int(type, amount.toLong()))
        return when {
            value == (1L shl high) + 1 -> builder.binary(Opcode.IAdd, type, shifted(high), x)
            value == (1L shl (high + 1)) - 1 && high + 1 < type.bits -> builder.binary(Opcode.ISub, type, shifted(high + 1), x)
            else -> null
        }
    }
}
