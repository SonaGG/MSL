package gg.sona.msl.dxil

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrVector

object DxilNativeIntrinsics {
    val UNARY = mapOf(
        Intrinsic.FAbs to DxilOpcode.FAbs,
        Intrinsic.Saturate to DxilOpcode.Saturate,
        Intrinsic.Sin to DxilOpcode.Sin,
        Intrinsic.Cos to DxilOpcode.Cos,
        Intrinsic.Tan to DxilOpcode.Tan,
        Intrinsic.Asin to DxilOpcode.Asin,
        Intrinsic.Acos to DxilOpcode.Acos,
        Intrinsic.Atan to DxilOpcode.Atan,
        Intrinsic.Sinh to DxilOpcode.Hsin,
        Intrinsic.Cosh to DxilOpcode.Hcos,
        Intrinsic.Tanh to DxilOpcode.Htan,
        Intrinsic.Exp2 to DxilOpcode.Exp,
        Intrinsic.Log2 to DxilOpcode.Log,
        Intrinsic.Sqrt to DxilOpcode.Sqrt,
        Intrinsic.Rsqrt to DxilOpcode.Rsqrt,
        Intrinsic.Rint to DxilOpcode.RoundNe,
        Intrinsic.Floor to DxilOpcode.RoundNi,
        Intrinsic.Ceil to DxilOpcode.RoundPi,
        Intrinsic.Trunc to DxilOpcode.RoundZ,
        Intrinsic.Dfdx to DxilOpcode.DerivCoarseX,
        Intrinsic.Dfdy to DxilOpcode.DerivCoarseY,
    )

    val BINARY = mapOf(
        Intrinsic.FMin to DxilOpcode.FMin,
        Intrinsic.FMax to DxilOpcode.FMax,
        Intrinsic.SMin to DxilOpcode.IMin,
        Intrinsic.SMax to DxilOpcode.IMax,
        Intrinsic.UMin to DxilOpcode.UMin,
        Intrinsic.UMax to DxilOpcode.UMax,
    )

    val SPECIAL_FLOAT = mapOf(
        Intrinsic.IsNan to DxilOpcode.IsNaN,
        Intrinsic.IsInf to DxilOpcode.IsInf,
        Intrinsic.IsFinite to DxilOpcode.IsFinite,
        Intrinsic.IsNormal to DxilOpcode.IsNormal,
    )

    private val INTEGER = setOf(
        Intrinsic.Popcount, Intrinsic.ReverseBits, Intrinsic.Clz, Intrinsic.Ctz, Intrinsic.SExtractBits,
        Intrinsic.UExtractBits, Intrinsic.InsertBits, Intrinsic.SMulHi, Intrinsic.UMulHi,
    )

    private val ALWAYS = setOf(Intrinsic.All, Intrinsic.Any)

    fun isNative(intrinsic: Intrinsic, instruction: Instruction): Boolean {
        if (intrinsic in ALWAYS) return true
        val type = instruction.operands.firstOrNull()?.type ?: instruction.type
        if (type !is IrScalar && type !is IrVector) return false
        val scalar = type.scalar
        return when {
            intrinsic in UNARY || intrinsic in BINARY && scalar is IrFloat -> scalar is IrFloat && scalar.bits != 64
            intrinsic in BINARY -> scalar is IrInt && scalar.bits != 8
            intrinsic in SPECIAL_FLOAT -> scalar is IrFloat && scalar.bits != 64
            intrinsic == Intrinsic.Fwidth || intrinsic == Intrinsic.Fma || intrinsic == Intrinsic.Dot -> scalar is IrFloat && scalar.bits != 64
            intrinsic in INTEGER -> scalar is IrInt && (scalar.bits == 32 || intrinsic in COUNTING && scalar.bits != 64)
            else -> intrinsic in ALWAYS
        }
    }

    private val COUNTING = setOf(Intrinsic.Popcount, Intrinsic.ReverseBits, Intrinsic.Clz, Intrinsic.Ctz)
}
