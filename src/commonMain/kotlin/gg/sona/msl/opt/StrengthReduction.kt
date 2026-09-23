package gg.sona.msl.opt

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
                    Opcode.UDiv, Opcode.URem -> unsignedDivision(builder, instruction)
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

    private fun unsignedDivision(builder: IrBuilder, instruction: Instruction): Value? {
        val type = instruction.type as? IrInt ?: return null
        if (type.bits != 32 || type.signed) return null
        val divisor = (instruction.operands[1] as? ConstantScalar)?.bits?.and(0xFFFFFFFFL) ?: return null
        if (divisor < 3 || divisor and (divisor - 1) == 0L) return null
        val x = instruction.operands[0]
        if (divisor > 0x80000000L) {
            val above = builder.binary(Opcode.UGreaterEqual, IrBool, x, ConstantScalar.int(type, divisor))
            return if (instruction.opcode == Opcode.UDiv) {
                builder.select(above, ConstantScalar.int(type, 1), ConstantScalar.int(type, 0))
            } else {
                builder.select(above, builder.binary(Opcode.ISub, type, x, ConstantScalar.int(type, divisor)), x)
            }
        }
        val probe = Instruction(Opcode.Intrinsic, type, listOf(instruction.operands[0], instruction.operands[0]))
        if (!isNative(Intrinsic.UMulHi, probe)) return null
        var shift = 0
        while ((1L shl shift) < divisor) shift++
        val multiplier = ((1L shl 32).toULong() * ((1L shl shift) - divisor).toULong() / divisor.toULong() + 1uL).toLong() and 0xFFFFFFFFL
        val high = builder.intrinsic(Intrinsic.UMulHi, type, listOf(x, ConstantScalar.int(type, multiplier)))
        val half = builder.binary(Opcode.LShr, type, builder.binary(Opcode.ISub, type, x, high), ConstantScalar.int(type, 1))
        val quotient = builder.binary(Opcode.LShr, type, builder.binary(Opcode.IAdd, type, high, half), ConstantScalar.int(type, (shift - 1).toLong()))
        if (instruction.opcode == Opcode.UDiv) return quotient
        return builder.binary(Opcode.ISub, type, x, builder.binary(Opcode.IMul, type, quotient, ConstantScalar.int(type, divisor)))
    }

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
