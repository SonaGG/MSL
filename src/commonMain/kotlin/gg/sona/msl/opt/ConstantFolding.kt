package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.util.HalfFloat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

object ConstantFolding {
    fun fold(instruction: Instruction): IrConstant? = fold(instruction, instruction.operands)

    fun fold(instruction: Instruction, operands: List<Value>): IrConstant? {
        if (operands.any { !isFoldable(it) }) return null
        val constants = operands.map { it as IrConstant }
        val type = instruction.type
        return when (instruction.opcode) {
            Opcode.CompositeConstruct -> construct(type, constants)
            Opcode.CompositeExtract -> extract(constants[0], instruction.literals)
            Opcode.CompositeInsert -> insert(constants[1], instruction.literals, 0, constants[0])
            Opcode.VectorShuffle -> {
                val lanes = Constants.scalars(constants[0]) + Constants.scalars(constants[1])
                ConstantComposite(type, instruction.literals.map { lanes[it] })
            }

            Opcode.VectorExtractDynamic -> {
                val index = (constants[1] as ConstantScalar).bits
                val lanes = Constants.scalars(constants[0])
                if (index < 0 || index >= lanes.size) null else lanes[index.toInt()]
            }

            Opcode.VectorInsertDynamic -> {
                val index = (constants[2] as ConstantScalar).bits
                val lanes = Constants.scalars(constants[0]).toMutableList()
                if (index < 0 || index >= lanes.size) null else ConstantComposite(type, lanes.also { it[index.toInt()] = constants[1] })
            }

            Opcode.Select -> {
                val condition = constants[0]
                if (condition is ConstantScalar) {
                    if (condition.asBoolean) constants[1] else constants[2]
                } else {
                    val conditions = Constants.scalars(condition)
                    val a = Constants.scalars(constants[1])
                    val b = Constants.scalars(constants[2])
                    ConstantComposite(type, conditions.indices.map { if ((conditions[it] as ConstantScalar).asBoolean) a[it] else b[it] })
                }
            }

            Opcode.Bitcast -> bitcast(constants[0], type)
            Opcode.Intrinsic -> intrinsic(instruction, constants)
            Opcode.MatrixTimesVector, Opcode.VectorTimesMatrix, Opcode.MatrixTimesMatrix, Opcode.MatrixTimesScalar, Opcode.VectorTimesScalar -> null
            else -> lanes(type, constants) { values, scalar -> scalarOp(instruction.opcode, values, scalar, sourceScalar(operands[0].type)) }
        }
    }

    fun isFoldable(value: Value): Boolean = when (value) {
        is ConstantScalar -> true
        is ConstantNull -> value.type is IrScalar || value.type is IrVector || value.type is IrMatrix
        is ConstantComposite -> value.elements.all { isFoldable(it) }
        else -> false
    }

    private fun sourceScalar(type: IrType): IrScalar = when (type) {
        is IrScalar -> type
        is IrVector -> type.element
        is IrMatrix -> type.column.element
        else -> IrInt.I32
    }

    private fun construct(type: IrType, parts: List<IrConstant>): IrConstant? {
        if (type is IrVector) {
            val lanes = parts.flatMap { if (it.type is IrVector) Constants.scalars(it) else listOf(it) }
            if (lanes.size != type.count) return null
            return ConstantComposite(type, lanes)
        }
        return ConstantComposite(type, parts)
    }

    private fun elements(value: IrConstant): List<IrConstant>? = when (value) {
        is ConstantComposite -> value.elements
        is ConstantNull -> when (val type = value.type) {
            is IrVector -> List(type.count) { Constants.zero(type.element) }
            is IrMatrix -> List(type.columns) { ConstantNull(type.column) }
            else -> null
        }

        else -> null
    }

    private fun extract(value: IrConstant, path: IntArray): IrConstant? {
        var current = value
        for (index in path) current = elements(current)?.getOrNull(index) ?: return null
        return current
    }

    private fun insert(value: IrConstant, path: IntArray, depth: Int, element: IrConstant): IrConstant? {
        if (depth == path.size) return element
        val parts = elements(value)?.toMutableList() ?: return null
        val index = path[depth]
        if (index !in parts.indices) return null
        parts[index] = insert(parts[index], path, depth + 1, element) ?: return null
        return ConstantComposite(value.type, parts)
    }

    private fun lanes(type: IrType, constants: List<IrConstant>, operation: (List<ConstantScalar>, IrScalar) -> ConstantScalar?): IrConstant? {
        if (type is IrVector) {
            val lanes = constants.map { constant -> Constants.scalars(constant).map { it as? ConstantScalar ?: return null } }
            val result = List(type.count) { lane -> operation(lanes.map { list -> list.getOrElse(lane) { list[0] } }, type.element) ?: return null }
            return ConstantComposite(type, result)
        }
        if (type !is IrScalar) return null
        val scalars = constants.map { constant -> (if (constant is ConstantNull) Constants.zero(constant.type) else constant) as? ConstantScalar ?: return null }
        return operation(scalars, type)
    }

    fun round(type: IrFloat, value: Double): Double = when (type.bits) {
        16 -> HalfFloat.round(value)
        32 -> value.toFloat().toDouble()
        else -> value
    }

    private fun mask(value: Long, bits: Int): Long = if (bits >= 64) value else value and ((1L shl bits) - 1)

    private fun signExtend(value: Long, bits: Int): Long = if (bits >= 64) value else (value shl (64 - bits)) shr (64 - bits)

    private fun float(type: IrScalar, value: Double): ConstantScalar? {
        if (type !is IrFloat) return null
        return ConstantScalar.float(type, round(type, value))
    }

    private fun scalarOp(opcode: Opcode, v: List<ConstantScalar>, type: IrScalar, source: IrScalar): ConstantScalar? {
        fun f(i: Int) = v[i].asDouble
        fun l(i: Int) = v[i].bits
        fun b(i: Int) = v[i].asBoolean
        fun int(value: Long) = (type as? IrInt)?.let { ConstantScalar.int(it, value) }
        fun bool(value: Boolean) = ConstantScalar.bool(value)
        val bits = source.bits
        val shift = { l(1) % bits }
        return when (opcode) {
            Opcode.IAdd -> int(l(0) + l(1))
            Opcode.ISub -> int(l(0) - l(1))
            Opcode.IMul -> int(l(0) * l(1))
            Opcode.SDiv -> if (l(1) == 0L) null else int(signExtend(l(0), bits) / signExtend(l(1), bits))
            Opcode.UDiv -> if (l(1) == 0L) null else int((mask(l(0), bits).toULong() / mask(l(1), bits).toULong()).toLong())
            Opcode.SRem -> if (l(1) == 0L) null else int(signExtend(l(0), bits) % signExtend(l(1), bits))
            Opcode.URem -> if (l(1) == 0L) null else int((mask(l(0), bits).toULong() % mask(l(1), bits).toULong()).toLong())
            Opcode.FAdd -> float(type, f(0) + f(1))
            Opcode.FSub -> float(type, f(0) - f(1))
            Opcode.FMul -> float(type, f(0) * f(1))
            Opcode.FDiv -> if (f(1) == 0.0) null else float(type, f(0) / f(1))
            Opcode.FRem -> if (f(1) == 0.0) null else float(type, f(0) - f(1) * kotlin.math.truncate(f(0) / f(1)))
            Opcode.FNeg -> float(type, -f(0))
            Opcode.INeg -> int(-l(0))
            Opcode.And -> if (type is IrBool) bool(b(0) && b(1)) else int(l(0) and l(1))
            Opcode.Or -> if (type is IrBool) bool(b(0) || b(1)) else int(l(0) or l(1))
            Opcode.Xor -> if (type is IrBool) bool(b(0) xor b(1)) else int(l(0) xor l(1))
            Opcode.Not -> if (type is IrBool) bool(!b(0)) else int(l(0).inv())
            Opcode.Shl -> int(l(0) shl shift().toInt())
            Opcode.LShr -> int(mask(l(0), bits) ushr shift().toInt())
            Opcode.AShr -> int(signExtend(l(0), bits) shr shift().toInt())
            Opcode.LogicalAnd -> bool(b(0) && b(1))
            Opcode.LogicalOr -> bool(b(0) || b(1))
            Opcode.LogicalNot -> bool(!b(0))
            Opcode.LogicalEqual -> bool(b(0) == b(1))
            Opcode.LogicalNotEqual -> bool(b(0) != b(1))
            Opcode.IEqual -> bool(mask(l(0), bits) == mask(l(1), bits))
            Opcode.INotEqual -> bool(mask(l(0), bits) != mask(l(1), bits))
            Opcode.SLess -> bool(signExtend(l(0), bits) < signExtend(l(1), bits))
            Opcode.SLessEqual -> bool(signExtend(l(0), bits) <= signExtend(l(1), bits))
            Opcode.SGreater -> bool(signExtend(l(0), bits) > signExtend(l(1), bits))
            Opcode.SGreaterEqual -> bool(signExtend(l(0), bits) >= signExtend(l(1), bits))
            Opcode.ULess -> bool(mask(l(0), bits).toULong().compareTo(mask(l(1), bits).toULong()) < 0)
            Opcode.ULessEqual -> bool(mask(l(0), bits).toULong().compareTo(mask(l(1), bits).toULong()) <= 0)
            Opcode.UGreater -> bool(mask(l(0), bits).toULong().compareTo(mask(l(1), bits).toULong()) > 0)
            Opcode.UGreaterEqual -> bool(mask(l(0), bits).toULong().compareTo(mask(l(1), bits).toULong()) >= 0)
            Opcode.FEqual -> bool(f(0) == f(1))
            Opcode.FNotEqual -> bool(f(0) != f(1))
            Opcode.FLess -> bool(f(0) < f(1))
            Opcode.FLessEqual -> bool(f(0) <= f(1))
            Opcode.FGreater -> bool(f(0) > f(1))
            Opcode.FGreaterEqual -> bool(f(0) >= f(1))
            Opcode.SToF -> float(type, signExtend(l(0), bits).toDouble())
            Opcode.UToF -> if (bits == 64 && l(0) < 0) null else float(type, mask(l(0), bits).toDouble())
            Opcode.FToS, Opcode.FToU -> {
                val value = f(0)
                val target = type as? IrInt ?: return null
                val low = if (target.signed) -(1L shl (target.bits - 1)).toDouble() else 0.0
                val high = if (target.signed) ((1L shl (target.bits - 1)) - 1).toDouble() else ((1L shl minOf(target.bits, 63)) - 1).toDouble()
                if (value.isNaN() || value < low || value > high) null else int(value.toLong())
            }

            Opcode.FConvert -> float(type, f(0))
            Opcode.SConvert -> int(signExtend(l(0), bits))
            Opcode.UConvert -> int(mask(l(0), bits))
            else -> null
        }
    }

    private fun bitcast(value: IrConstant, target: IrType): IrConstant? {
        val source = value.type
        val scalars = Constants.scalars(value).map { it as? ConstantScalar ?: return null }
        if (source is IrMatrix || target is IrMatrix) return null
        var stream = 0L
        var shift = 0
        for (scalar in scalars) {
            val width = scalar.type.bits
            if (scalar.type is IrBool) return null
            val bits = when (val type = scalar.type) {
                is IrFloat -> when (type.bits) {
                    16 -> HalfFloat.fromFloat(scalar.asDouble.toFloat()).toLong()
                    32 -> scalar.asDouble.toFloat().toRawBits().toLong()
                    else -> scalar.asDouble.toRawBits()
                }

                else -> scalar.bits
            }
            if (shift + width > 64) return null
            stream = stream or (mask(bits, width) shl shift)
            shift += width
        }
        val element = target.scalar
        if (element is IrBool) return null
        val count = target.componentCount
        if (element.bits * count != shift) return null
        val lanes = List(count) { lane ->
            val bits = mask(stream ushr (lane * element.bits), element.bits)
            when (element) {
                is IrFloat -> ConstantScalar.float(
                    element,
                    when (element.bits) {
                        16 -> HalfFloat.toFloat(bits.toInt()).toDouble()
                        32 -> Float.fromBits(bits.toInt()).toDouble()
                        else -> Double.fromBits(bits)
                    },
                )

                is IrInt -> ConstantScalar.int(element, bits)
            }
        }
        return if (target is IrVector) ConstantComposite(target, lanes) else lanes[0]
    }

    private fun intrinsic(instruction: Instruction, constants: List<IrConstant>): IrConstant? {
        val intrinsic = instruction.intrinsic ?: return null
        if (!intrinsic.isPure) return null
        val type = instruction.type
        fun unary(operation: (Double) -> Double) = lanes(type, constants) { v, t -> float(t, operation(v[0].asDouble)) }
        fun binary(operation: (Double, Double) -> Double) = lanes(type, constants) { v, t -> float(t, operation(v[0].asDouble, v[1].asDouble)) }
        fun ternary(operation: (Double, Double, Double) -> Double) = lanes(type, constants) { v, t -> float(t, operation(v[0].asDouble, v[1].asDouble, v[2].asDouble)) }
        fun integer(operation: (List<Long>, IrInt) -> Long?) = lanes(type, constants) { v, t ->
            val target = t as? IrInt ?: return@lanes null
            operation(v.map { it.bits }, target)?.let { ConstantScalar.int(target, it) }
        }

        fun vector(index: Int): List<Double>? = Constants.scalars(constants[index]).map { (it as? ConstantScalar)?.asDouble ?: return null }
        return when (intrinsic) {
            Intrinsic.FAbs -> unary { abs(it) }
            Intrinsic.FSign -> unary { if (it > 0) 1.0 else if (it < 0) -1.0 else 0.0 }
            Intrinsic.Floor -> unary { kotlin.math.floor(it) }
            Intrinsic.Ceil -> unary { kotlin.math.ceil(it) }
            Intrinsic.Rint -> unary { kotlin.math.round(it) }
            Intrinsic.Trunc -> unary { kotlin.math.truncate(it) }
            Intrinsic.Sqrt -> unary { sqrt(it) }
            Intrinsic.Rsqrt -> unary { 1.0 / sqrt(it) }
            Intrinsic.Sin -> unary { kotlin.math.sin(it) }
            Intrinsic.Cos -> unary { kotlin.math.cos(it) }
            Intrinsic.Tan -> unary { kotlin.math.tan(it) }
            Intrinsic.Asin -> unary { kotlin.math.asin(it) }
            Intrinsic.Acos -> unary { kotlin.math.acos(it) }
            Intrinsic.Atan -> unary { kotlin.math.atan(it) }
            Intrinsic.Exp -> unary { kotlin.math.exp(it) }
            Intrinsic.Exp2 -> unary { Math.pow(2.0, it) }
            Intrinsic.Log -> unary { kotlin.math.ln(it) }
            Intrinsic.Log2 -> unary { kotlin.math.log2(it) }
            Intrinsic.Sinpi -> unary { kotlin.math.sin(it * PI) }
            Intrinsic.Cospi -> unary { kotlin.math.cos(it * PI) }
            Intrinsic.Saturate -> unary { it.coerceIn(0.0, 1.0) }
            Intrinsic.Pow, Intrinsic.Powr -> binary { x, y -> Math.pow(x, y) }
            Intrinsic.Atan2 -> binary { y, x -> kotlin.math.atan2(y, x) }
            Intrinsic.FMin -> binary { a, b -> minOf(a, b) }
            Intrinsic.FMax -> binary { a, b -> maxOf(a, b) }
            Intrinsic.Step -> binary { edge, x -> if (x < edge) 0.0 else 1.0 }
            Intrinsic.Fma -> ternary { a, b, c -> a * b + c }
            Intrinsic.FClamp -> ternary { x, lo, hi -> minOf(maxOf(x, lo), hi) }
            Intrinsic.Mix -> ternary { a, b, t -> a + (b - a) * t }
            Intrinsic.SMin -> integer { v, t -> minOf(signExtend(v[0], t.bits), signExtend(v[1], t.bits)) }
            Intrinsic.SMax -> integer { v, t -> maxOf(signExtend(v[0], t.bits), signExtend(v[1], t.bits)) }
            Intrinsic.UMin -> integer { v, t -> if (mask(v[0], t.bits).toULong().compareTo(mask(v[1], t.bits).toULong()) < 0) v[0] else v[1] }
            Intrinsic.UMax -> integer { v, t -> if (mask(v[0], t.bits).toULong().compareTo(mask(v[1], t.bits).toULong()) > 0) v[0] else v[1] }
            Intrinsic.SAbs -> integer { v, t -> abs(signExtend(v[0], t.bits)) }
            Intrinsic.Popcount -> integer { v, t -> mask(v[0], t.bits).countOneBits().toLong() }
            Intrinsic.Dot -> {
                val a = vector(0) ?: return null
                val b = vector(1) ?: return null
                float(type as IrScalar, a.zip(b).sumOf { it.first * it.second })
            }

            Intrinsic.All, Intrinsic.Any -> {
                val lanes = Constants.scalars(constants[0]).map { (it as? ConstantScalar)?.asBoolean ?: return null }
                ConstantScalar.bool(if (intrinsic == Intrinsic.All) lanes.all { it } else lanes.any { it })
            }

            else -> null
        }
    }
}
