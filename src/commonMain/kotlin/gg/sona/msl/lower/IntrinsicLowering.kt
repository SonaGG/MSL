package gg.sona.msl.lower

import gg.sona.msl.hir.AtomicOperation
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.GlobalVariable
import gg.sona.msl.hir.HAtomic
import gg.sona.msl.hir.HConstruct
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HIntrinsic
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.HTextureOperation
import gg.sona.msl.hir.HVariableRef
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.TextureOperation
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.ir.Value
import gg.sona.msl.types.SampleOptionKind

class IntrinsicLowering(private val lowering: FunctionLowering) {
    private val builder = lowering.builder

    private fun type(expression: HExpr): IrType = lowering.lowerType(expression.type)

    fun intrinsic(expression: HIntrinsic): Value? {
        val resultType = type(expression)
        val intrinsic = expression.intrinsic
        when (intrinsic) {
            Intrinsic.ThreadgroupBarrier, Intrinsic.SimdgroupBarrier, Intrinsic.MemoryFence -> {
                val flags = constantInteger(expression.arguments[0])
                val order = expression.arguments.getOrNull(1)?.let { constantInteger(it) } ?: 0
                builder.intrinsic(intrinsic, IrVoid, emptyList(), intArrayOf(flags, order))
                return null
            }

            Intrinsic.IsFunctionConstantDefined -> {
                val global = (expression.arguments[0] as HVariableRef).variable as GlobalVariable
                return lowering.lowering.functionConstantDefined(global)
            }

            Intrinsic.Discard -> {
                builder.intrinsic(Intrinsic.Discard, IrVoid, emptyList())
                return null
            }

            Intrinsic.Sincos, Intrinsic.Modf, Intrinsic.Frexp -> return outParameter(expression, resultType)
            else -> Unit
        }
        val arguments = expression.arguments.map { lowering.rvalue(it) }
        if (resultType == IrVoid) {
            builder.intrinsic(intrinsic, IrVoid, arguments)
            return null
        }
        return expand(intrinsic, resultType, arguments)
    }

    private fun constantInteger(expression: HExpr): Int {
        val literal = expression as? HLiteral
        return when (val value = literal?.value) {
            is EnumConstant -> value.value.toInt()
            is ScalarConstant -> value.asLong.toInt()
            else -> {
                val folded = lowering.rvalue(expression)
                if (folded is ConstantScalar) {
                    folded.bits.toInt()
                } else {
                    lowering.error(expression.location, "memory flags must be a constant expression")
                    0
                }
            }
        }
    }

    private fun outParameter(expression: HIntrinsic, resultType: IrType): Value {
        val value = lowering.rvalue(expression.arguments[0])
        val target = lowering.lvalue(expression.arguments[1])
        return when (expression.intrinsic) {
            Intrinsic.Sincos -> {
                lowering.store(target, builder.intrinsic(Intrinsic.Cos, resultType, listOf(value)))
                builder.intrinsic(Intrinsic.Sin, resultType, listOf(value))
            }

            Intrinsic.Modf -> {
                val whole = builder.intrinsic(Intrinsic.Trunc, resultType, listOf(value))
                lowering.store(target, whole)
                builder.binary(Opcode.FSub, resultType, value, whole)
            }

            else -> {
                val exponentType = lowering.lowerType(expression.arguments[1].type)
                lowering.store(target, builder.intrinsic(Intrinsic.FrexpExponent, exponentType, listOf(value)))
                builder.intrinsic(Intrinsic.FrexpMantissa, resultType, listOf(value))
            }
        }
    }

    private fun expand(intrinsic: Intrinsic, type: IrType, arguments: List<Value>): Value {
        val scalar = arguments.firstOrNull()?.type?.scalar
        val isFloat = scalar is IrFloat
        val signed = scalar is IrInt && scalar.signed
        fun minimum(a: Value, b: Value): Value = builder.intrinsic(
            if (isFloat) Intrinsic.FMin else if (signed) Intrinsic.SMin else Intrinsic.UMin,
            type,
            listOf(a, b),
        )

        fun maximum(a: Value, b: Value): Value = builder.intrinsic(
            if (isFloat) Intrinsic.FMax else if (signed) Intrinsic.SMax else Intrinsic.UMax,
            type,
            listOf(a, b),
        )
        return when (intrinsic) {
            Intrinsic.LengthSquared -> dot(arguments[0], arguments[0], type)
            Intrinsic.DistanceSquared -> {
                val difference = builder.binary(Opcode.FSub, arguments[0].type, arguments[0], arguments[1])
                dot(difference, difference, type)
            }

            Intrinsic.Dot -> dot(arguments[0], arguments[1], type)
            Intrinsic.Min3 -> minimum(minimum(arguments[0], arguments[1]), arguments[2])
            Intrinsic.Max3 -> maximum(maximum(arguments[0], arguments[1]), arguments[2])
            Intrinsic.Median3 -> maximum(minimum(arguments[0], arguments[1]), minimum(maximum(arguments[0], arguments[1]), arguments[2]))
            Intrinsic.Mul24 -> builder.binary(Opcode.IMul, type, arguments[0], arguments[1])
            Intrinsic.Mad24 -> builder.binary(Opcode.IAdd, type, builder.binary(Opcode.IMul, type, arguments[0], arguments[1]), arguments[2])
            Intrinsic.MadHi -> {
                val high = builder.intrinsic(if (signed) Intrinsic.SMulHi else Intrinsic.UMulHi, type, arguments.take(2))
                builder.binary(Opcode.IAdd, type, high, arguments[2])
            }

            Intrinsic.MadSat -> madSat(arguments, type, signed)
            Intrinsic.Select -> builder.select(arguments[2], arguments[1], arguments[0])
            else -> builder.intrinsic(intrinsic, type, arguments)
        }
    }

    private fun dot(left: Value, right: Value, type: IrType): Value {
        if (left.type is IrScalar) return builder.binary(Opcode.FMul, type, left, right)
        return builder.intrinsic(Intrinsic.Dot, type, listOf(left, right))
    }

    private fun madSat(arguments: List<Value>, type: IrType, signed: Boolean): Value {
        val scalar = type.scalar as IrInt
        val wide = IrInt.of(scalar.bits * 2, signed).takeIf { scalar.bits <= 32 } ?: return builder.binary(
            Opcode.IAdd,
            type,
            builder.binary(Opcode.IMul, type, arguments[0], arguments[1]),
            arguments[2],
        )
        val wideType = if (type is IrVector) IrVector.of(wide, type.count) else wide
        val extend = if (signed) Opcode.SConvert else Opcode.UConvert
        val a = builder.unary(extend, wideType, arguments[0])
        val b = builder.unary(extend, wideType, arguments[1])
        val c = builder.unary(extend, wideType, arguments[2])
        val sum = builder.binary(Opcode.IAdd, wideType, builder.binary(Opcode.IMul, wideType, a, b), c)
        val max = if (signed) (1L shl (scalar.bits - 1)) - 1 else (1L shl scalar.bits) - 1
        val min = if (signed) -(1L shl (scalar.bits - 1)) else 0L
        val clamped = builder.intrinsic(
            if (signed) Intrinsic.SClamp else Intrinsic.UClamp,
            wideType,
            listOf(sum, Constants.splat(wideType, ConstantScalar.int(wide, min)), Constants.splat(wideType, ConstantScalar.int(wide, max))),
        )
        return builder.unary(extend, type, clamped)
    }

    fun texture(expression: HTextureOperation): Value? {
        val resultType = type(expression)
        if (expression.operation == TextureOperation.Fence) return null
        val texture = lowering.rvalue(expression.texture)
        val operands = ArrayList<Value>()
        operands.add(texture)
        var mask = TextureOperands.None
        var extra = 0
        expression.sampler?.let {
            operands.add(lowering.rvalue(it))
            mask += TextureOperands.Sampler
        }
        expression.coordinate?.let { operands.add(lowering.rvalue(it)) }
        expression.arrayIndex?.let {
            operands.add(lowering.rvalue(it))
            mask += TextureOperands.ArrayIndex
        }
        val option = expression.option
        if (option != null) {
            val values = (option as? HConstruct)?.arguments?.map { lowering.rvalue(it) } ?: run {
                lowering.error(option.location, "sample options must be constructed inline")
                return null
            }
            when (expression.optionKind) {
                SampleOptionKind.Bias -> mask += TextureOperands.Bias
                SampleOptionKind.Level -> mask += TextureOperands.Lod
                else -> mask += TextureOperands.Gradient
            }
            operands.addAll(values)
        }
        expression.minLodClamp?.let {
            val value = (it as? HConstruct)?.arguments?.singleOrNull()?.let { argument -> lowering.rvalue(argument) } ?: run {
                lowering.error(it.location, "min_lod_clamp must be constructed inline")
                return null
            }
            operands.add(value)
            mask += TextureOperands.MinLod
        }
        expression.offset?.let {
            operands.add(lowering.rvalue(it))
            mask += TextureOperands.Offset
        }
        expression.compareValue?.let {
            operands.add(lowering.rvalue(it))
            mask += TextureOperands.Compare
        }
        val intrinsic = when (expression.operation) {
            TextureOperation.Sample -> Intrinsic.TextureSample
            TextureOperation.SampleCompare -> Intrinsic.TextureSampleCompare
            TextureOperation.Gather -> {
                extra = expression.component
                Intrinsic.TextureGather
            }

            TextureOperation.GatherCompare -> Intrinsic.TextureGatherCompare
            TextureOperation.Read -> {
                expression.sampleOrLod?.let {
                    operands.add(lowering.rvalue(it))
                    mask += if (isMultisampled(expression)) TextureOperands.SampleIndex else TextureOperands.Lod
                }
                Intrinsic.TextureRead
            }

            TextureOperation.Write -> {
                expression.sampleOrLod?.let {
                    operands.add(lowering.rvalue(it))
                    mask += TextureOperands.Lod
                }
                operands.add(lowering.rvalue(expression.value!!))
                mask += TextureOperands.Texel
                Intrinsic.TextureWrite
            }

            TextureOperation.GetWidth, TextureOperation.GetHeight, TextureOperation.GetDepth, TextureOperation.GetArraySize -> {
                extra = when (expression.operation) {
                    TextureOperation.GetWidth -> 0
                    TextureOperation.GetHeight -> 1
                    TextureOperation.GetDepth -> 2
                    else -> 3
                }
                expression.sampleOrLod?.let {
                    operands.add(lowering.rvalue(it))
                    mask += TextureOperands.Lod
                }
                Intrinsic.TextureSize
            }

            TextureOperation.GetNumMipLevels -> Intrinsic.TextureLevels
            TextureOperation.GetNumSamples -> Intrinsic.TextureSamples
            TextureOperation.CalculateClampedLod, TextureOperation.CalculateUnclampedLod -> {
                extra = if (expression.operation == TextureOperation.CalculateClampedLod) 1 else 0
                Intrinsic.TextureCalculateLod
            }

            TextureOperation.Fence -> return null
        }
        val instruction = builder.intrinsic(intrinsic, resultType, operands, intArrayOf(mask.bits, extra))
        return if (resultType == IrVoid) null else instruction
    }

    private fun isMultisampled(expression: HTextureOperation): Boolean =
        (expression.texture.type as gg.sona.msl.types.TextureType).kind.isMultisampled

    fun atomic(expression: HAtomic): Value? {
        val pointer = lowering.rvalue(expression.pointer)
        val resultType = type(expression)
        val element = ((pointer.type as? IrPointer)?.pointee ?: resultType).scalar
        val scope = if ((pointer.type as? IrPointer)?.storage == StorageClass.Workgroup) 1 else 0
        val literals = intArrayOf(scope)
        return when (expression.operation) {
            AtomicOperation.Load -> builder.intrinsic(Intrinsic.AtomicLoad, element, listOf(pointer), literals)
            AtomicOperation.Store -> {
                builder.intrinsic(Intrinsic.AtomicStore, IrVoid, listOf(pointer, lowering.rvalue(expression.value!!)), literals)
                null
            }

            AtomicOperation.CompareExchange -> {
                val expectedPointer = lowering.rvalue(expression.comparand!!)
                val expected = builder.load(expectedPointer)
                val desired = lowering.rvalue(expression.value!!)
                val original = builder.intrinsic(Intrinsic.AtomicCompareExchange, element, listOf(pointer, desired, expected), literals)
                builder.store(expectedPointer, original)
                val equal = if (element is IrFloat) Opcode.FEqual else if (element is IrBool) Opcode.LogicalEqual else Opcode.IEqual
                builder.binary(equal, IrBool, original, expected)
            }

            else -> {
                var value = lowering.rvalue(expression.value!!)
                val intrinsic = when (expression.operation) {
                    AtomicOperation.Exchange -> Intrinsic.AtomicExchange
                    AtomicOperation.Add -> if (element is IrFloat) Intrinsic.AtomicFAdd else Intrinsic.AtomicAdd
                    AtomicOperation.Sub -> if (element is IrFloat) {
                        value = builder.unary(Opcode.FNeg, element, value)
                        Intrinsic.AtomicFAdd
                    } else {
                        Intrinsic.AtomicSub
                    }

                    AtomicOperation.And -> Intrinsic.AtomicAnd
                    AtomicOperation.Or -> Intrinsic.AtomicOr
                    AtomicOperation.Xor -> Intrinsic.AtomicXor
                    AtomicOperation.Min -> if (element is IrInt && element.signed) Intrinsic.AtomicSMin else Intrinsic.AtomicUMin
                    AtomicOperation.Max -> if (element is IrInt && element.signed) Intrinsic.AtomicSMax else Intrinsic.AtomicUMax
                    else -> error("unexpected atomic operation")
                }
                builder.intrinsic(intrinsic, element, listOf(pointer, value), literals)
            }
        }
    }
}
