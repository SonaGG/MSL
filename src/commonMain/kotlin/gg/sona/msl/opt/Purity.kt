package gg.sona.msl.opt

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value

object Purity {
    private val COMPUTATIONAL = setOf(
        Opcode.IAdd, Opcode.ISub, Opcode.IMul, Opcode.SDiv, Opcode.UDiv, Opcode.SRem, Opcode.URem,
        Opcode.FAdd, Opcode.FSub, Opcode.FMul, Opcode.FDiv, Opcode.FRem, Opcode.FNeg, Opcode.INeg,
        Opcode.And, Opcode.Or, Opcode.Xor, Opcode.Not, Opcode.Shl, Opcode.LShr, Opcode.AShr,
        Opcode.LogicalAnd, Opcode.LogicalOr, Opcode.LogicalNot, Opcode.LogicalEqual, Opcode.LogicalNotEqual,
        Opcode.IEqual, Opcode.INotEqual, Opcode.SLess, Opcode.SLessEqual, Opcode.SGreater, Opcode.SGreaterEqual,
        Opcode.ULess, Opcode.ULessEqual, Opcode.UGreater, Opcode.UGreaterEqual,
        Opcode.FEqual, Opcode.FNotEqual, Opcode.FLess, Opcode.FLessEqual, Opcode.FGreater, Opcode.FGreaterEqual,
        Opcode.Select, Opcode.SToF, Opcode.UToF, Opcode.FToS, Opcode.FToU, Opcode.FConvert, Opcode.SConvert, Opcode.UConvert,
        Opcode.Bitcast, Opcode.CompositeConstruct, Opcode.CompositeExtract, Opcode.CompositeInsert, Opcode.VectorShuffle,
        Opcode.VectorExtractDynamic, Opcode.VectorInsertDynamic, Opcode.MatrixTimesVector, Opcode.VectorTimesMatrix,
        Opcode.MatrixTimesMatrix, Opcode.MatrixTimesScalar, Opcode.VectorTimesScalar,
    )

    private val REPEATABLE_INTRINSICS = setOf(
        Intrinsic.Dfdx, Intrinsic.Dfdy, Intrinsic.Fwidth, Intrinsic.TextureSample, Intrinsic.TextureSampleCompare,
        Intrinsic.TextureGather, Intrinsic.TextureGatherCompare, Intrinsic.TextureCalculateLod, Intrinsic.TextureSize,
        Intrinsic.TextureLevels, Intrinsic.TextureSamples, Intrinsic.IsFunctionConstantDefined,
    )

    fun isFoldable(instruction: Instruction): Boolean = when (instruction.opcode) {
        in COMPUTATIONAL -> true
        Opcode.Intrinsic -> instruction.intrinsic!!.isPure
        else -> false
    }

    fun isRepeatable(instruction: Instruction): Boolean = when (instruction.opcode) {
        in COMPUTATIONAL, Opcode.AccessChain, Opcode.PtrOffset -> true
        Opcode.Load -> isReadOnly(instruction.operands[0])
        Opcode.Intrinsic -> {
            val intrinsic = instruction.intrinsic!!
            intrinsic.isPure || intrinsic in REPEATABLE_INTRINSICS ||
                (intrinsic == Intrinsic.TextureRead && (instruction.operands[0].type as? IrImage)?.access?.isStorage == false)
        }

        else -> false
    }

    fun isSpeculatable(instruction: Instruction): Boolean = when (instruction.opcode) {
        in COMPUTATIONAL, Opcode.AccessChain -> true
        Opcode.Intrinsic -> instruction.intrinsic!!.isPure
        Opcode.Load -> rootStorage(instruction.operands[0]) == StorageClass.Input
        else -> false
    }

    fun cost(instruction: Instruction): Int = when (instruction.opcode) {
        Opcode.CompositeExtract, Opcode.CompositeConstruct, Opcode.CompositeInsert, Opcode.VectorShuffle, Opcode.Bitcast, Opcode.AccessChain -> 0
        Opcode.SDiv, Opcode.UDiv, Opcode.SRem, Opcode.URem, Opcode.FDiv, Opcode.FRem -> 4
        Opcode.MatrixTimesMatrix -> 16
        Opcode.MatrixTimesVector, Opcode.VectorTimesMatrix -> 4
        Opcode.Intrinsic -> 4
        Opcode.Load -> 2
        else -> 1
    }

    fun rootGlobal(pointer: Value): GlobalVariable? {
        var current = pointer
        while (current is Instruction && (current.opcode == Opcode.AccessChain || current.opcode == Opcode.PtrOffset)) current = current.operands[0]
        return current as? GlobalVariable
    }

    fun rootStorage(pointer: Value): StorageClass? = rootGlobal(pointer)?.storage

    fun isReadOnly(pointer: Value): Boolean {
        val global = rootGlobal(pointer) ?: return false
        return when (global.storage) {
            StorageClass.Input, StorageClass.Uniform, StorageClass.UniformConstant, StorageClass.PushConstant -> true
            StorageClass.StorageBuffer -> global.resource?.readOnly == true
            StorageClass.Private -> global.isConstant
            else -> false
        }
    }
}
