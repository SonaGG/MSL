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

class IntrinsicExpander(private val b: IrBuilder, private val instruction: Instruction) {
    val type: IrType = instruction.type
    val args: List<Value> = instruction.operands

    fun constant(type: IrType, value: Double): IrConstant = Constants.splat(type, Constants.scalar(type.scalar, value))

    fun integer(type: IrType, value: Long): IrConstant = Constants.splat(type, Constants.integer(type.scalar, value))

    fun boolType(type: IrType): IrType = if (type is IrVector) IrVector.of(IrBool, type.count) else IrBool

    fun intType(type: IrType, signed: Boolean = false): IrType {
        val scalar = IrInt.of(type.scalar.bits, signed)
        return if (type is IrVector) IrVector.of(scalar, type.count) else scalar
    }

    fun call(intrinsic: Intrinsic, type: IrType, vararg operands: Value): Value = b.intrinsic(intrinsic, type, operands.toList())

    fun op(opcode: Opcode, type: IrType, left: Value, right: Value): Value = b.binary(opcode, type, left, right)

    fun fadd(x: Value, y: Value) = op(Opcode.FAdd, x.type, x, y)
    fun fsub(x: Value, y: Value) = op(Opcode.FSub, x.type, x, y)
    fun fmul(x: Value, y: Value) = op(Opcode.FMul, x.type, x, y)
    fun fdiv(x: Value, y: Value) = op(Opcode.FDiv, x.type, x, y)
    fun iadd(x: Value, y: Value) = op(Opcode.IAdd, x.type, x, y)
    fun isub(x: Value, y: Value) = op(Opcode.ISub, x.type, x, y)
    fun and(x: Value, y: Value) = op(Opcode.And, x.type, x, y)
    fun or(x: Value, y: Value) = op(Opcode.Or, x.type, x, y)
    fun xor(x: Value, y: Value) = op(Opcode.Xor, x.type, x, y)

    fun compare(opcode: Opcode, x: Value, y: Value) = op(opcode, boolType(x.type), x, y)

    fun select(condition: Value, x: Value, y: Value) = b.select(condition, x, y)

    fun bitcast(x: Value, to: IrType) = b.unary(Opcode.Bitcast, to, x)

    fun dot(x: Value, y: Value): Value =
        if (x.type is IrScalar) fmul(x, y) else call(Intrinsic.Dot, x.type.scalar, x, y)

    fun splatScalar(type: IrType, scalar: Value): Value = if (type is IrVector && scalar.type is IrScalar) b.splat(type, scalar) else scalar

    fun expand(): Value? {
        val x = args.getOrNull(0)
        val y = args.getOrNull(1)
        val z = args.getOrNull(2)
        return when (instruction.intrinsic!!) {
            Intrinsic.Round -> {
                val truncated = call(Intrinsic.Trunc, type, x!!)
                val distance = call(Intrinsic.FAbs, type, fsub(x, truncated))
                val away = compare(Opcode.FGreaterEqual, distance, constant(type, 0.5))
                select(away, fadd(truncated, call(Intrinsic.FSign, type, x)), truncated)
            }

            Intrinsic.Exp10 -> call(Intrinsic.Exp2, type, fmul(x!!, constant(type, ln(10.0) / ln(2.0))))
            Intrinsic.Log10 -> fmul(call(Intrinsic.Log2, type, x!!), constant(type, ln(2.0) / ln(10.0)))
            Intrinsic.Sinpi -> call(Intrinsic.Sin, type, fmul(x!!, constant(type, PI)))
            Intrinsic.Cospi -> call(Intrinsic.Cos, type, fmul(x!!, constant(type, PI)))
            Intrinsic.Tanpi -> call(Intrinsic.Tan, type, fmul(x!!, constant(type, PI)))
            Intrinsic.Copysign -> {
                val integer = intType(type)
                val bits = type.scalar.bits
                val signMask = integer(integer, 1L shl (bits - 1))
                val magnitudeMask = integer(integer, (1L shl (bits - 1)) - 1)
                val magnitude = and(bitcast(x!!, integer), magnitudeMask)
                val sign = and(bitcast(y!!, integer), signMask)
                bitcast(or(magnitude, sign), type)
            }

            Intrinsic.Fdim -> call(Intrinsic.FMax, type, fsub(x!!, y!!), constant(type, 0.0))
            Intrinsic.Saturate -> call(Intrinsic.FClamp, type, x!!, constant(type, 0.0), constant(type, 1.0))
            Intrinsic.Powr -> call(Intrinsic.Pow, type, x!!, y!!)
            Intrinsic.Fmod -> op(Opcode.FRem, type, x!!, y!!)
            Intrinsic.Fract -> {
                val fraction = fsub(x!!, call(Intrinsic.Floor, type, x))
                val limit = if (type.scalar.bits == 16) 1.0 - 1.0 / 2048.0 else Float.fromBits(0x3f7fffff).toDouble()
                call(Intrinsic.FMin, type, fraction, constant(type, limit))
            }

            Intrinsic.IsFinite -> {
                val nan = call(Intrinsic.IsNan, type, x!!)
                val inf = call(Intrinsic.IsInf, type, x)
                b.unary(Opcode.LogicalNot, type, op(Opcode.LogicalOr, type, nan, inf))
            }

            Intrinsic.IsNormal -> {
                val source = x!!.type
                val bits = source.scalar.bits
                val mantissa = if (bits == 16) 10 else 23
                val exponentMask = if (bits == 16) 0x1FL else 0xFFL
                val integer = intType(source)
                val exponent = and(op(Opcode.LShr, integer, bitcast(x, integer), integer(integer, mantissa.toLong())), integer(integer, exponentMask))
                val nonZero = compare(Opcode.INotEqual, exponent, integer(integer, 0))
                val notMax = compare(Opcode.INotEqual, exponent, integer(integer, exponentMask))
                op(Opcode.LogicalAnd, type, nonZero, notMax)
            }

            Intrinsic.SignBit -> {
                val integer = intType(x!!.type, signed = true)
                compare(Opcode.SLess, bitcast(x, integer), integer(integer, 0))
            }

            Intrinsic.SAbsDiff, Intrinsic.UAbsDiff -> {
                val signed = instruction.intrinsic == Intrinsic.SAbsDiff
                val greater = compare(if (signed) Opcode.SGreater else Opcode.UGreater, x!!, y!!)
                select(greater, isub(x, y), isub(y, x))
            }

            Intrinsic.UAddSat -> {
                val sum = iadd(x!!, y!!)
                select(compare(Opcode.ULess, sum, x), integer(type, -1), sum)
            }

            Intrinsic.USubSat -> select(compare(Opcode.ULess, x!!, y!!), integer(type, 0), isub(x, y))
            Intrinsic.SAddSat, Intrinsic.SSubSat -> {
                val add = instruction.intrinsic == Intrinsic.SAddSat
                val result = if (add) iadd(x!!, y!!) else isub(x!!, y!!)
                val overflowBits = if (add) and(xor(x, result), xor(y, result)) else and(xor(x, y), xor(x, result))
                val overflow = compare(Opcode.SLess, overflowBits, integer(type, 0))
                val bits = type.scalar.bits
                val max = integer(type, (1L shl (bits - 1)) - 1)
                val min = integer(type, -(1L shl (bits - 1)))
                val saturated = select(compare(Opcode.SLess, x, integer(type, 0)), min, max)
                select(overflow, saturated, result)
            }

            Intrinsic.SHAdd, Intrinsic.UHAdd, Intrinsic.SRHAdd, Intrinsic.URHAdd -> {
                val signed = instruction.intrinsic == Intrinsic.SHAdd || instruction.intrinsic == Intrinsic.SRHAdd
                val shift = if (signed) Opcode.AShr else Opcode.LShr
                val one = integer(type, 1)
                val halves = iadd(op(shift, type, x!!, one), op(shift, type, y!!, one))
                val carry = if (instruction.intrinsic == Intrinsic.SHAdd || instruction.intrinsic == Intrinsic.UHAdd) {
                    and(and(x, y), one)
                } else {
                    and(or(x, y), one)
                }
                iadd(halves, carry)
            }

            Intrinsic.Rotate -> {
                val unsigned = intType(type)
                val value = if ((type.scalar as IrInt).signed) bitcast(x!!, unsigned) else x!!
                val amount = if ((type.scalar as IrInt).signed) bitcast(y!!, unsigned) else y!!
                val bits = type.scalar.bits.toLong()
                val mask = integer(unsigned, bits - 1)
                val left = and(amount, mask)
                val right = and(isub(integer(unsigned, bits), left), mask)
                val rotated = or(op(Opcode.Shl, unsigned, value, left), op(Opcode.LShr, unsigned, value, right))
                if ((type.scalar as IrInt).signed) bitcast(rotated, type) else rotated
            }

            Intrinsic.Step -> select(compare(Opcode.FLess, y!!, splatScalar(y.type, x!!)), constant(type, 0.0), constant(type, 1.0))
            Intrinsic.Smoothstep -> {
                val edge0 = splatScalar(type, x!!)
                val edge1 = splatScalar(type, y!!)
                val t = call(Intrinsic.FClamp, type, fdiv(fsub(z!!, edge0), fsub(edge1, edge0)), constant(type, 0.0), constant(type, 1.0))
                fmul(fmul(t, t), fsub(constant(type, 3.0), fmul(constant(type, 2.0), t)))
            }

            Intrinsic.Mix -> fadd(x!!, fmul(fsub(y!!, x), splatScalar(type, z!!)))
            Intrinsic.FClamp -> call(Intrinsic.FMin, type, call(Intrinsic.FMax, type, x!!, y!!), z!!)
            Intrinsic.SClamp -> call(Intrinsic.SMin, type, call(Intrinsic.SMax, type, x!!, y!!), z!!)
            Intrinsic.UClamp -> call(Intrinsic.UMin, type, call(Intrinsic.UMax, type, x!!, y!!), z!!)
            Intrinsic.Normalize -> if (type is IrScalar) {
                call(Intrinsic.FSign, type, x!!)
            } else {
                val scale = call(Intrinsic.Rsqrt, type.scalar, dot(x!!, x))
                fmul(x, b.splat(type, scale))
            }

            Intrinsic.Length -> if (x!!.type is IrScalar) call(Intrinsic.FAbs, type, x) else call(Intrinsic.Sqrt, type, dot(x, x))
            Intrinsic.Distance -> {
                val difference = fsub(x!!, y!!)
                if (difference.type is IrScalar) {
                    call(Intrinsic.FAbs, type, difference)
                } else {
                    call(Intrinsic.Sqrt, type, dot(difference, difference))
                }
            }

            Intrinsic.Cross -> {
                val a1 = b.shuffle(x!!, x, intArrayOf(1, 2, 0))
                val b1 = b.shuffle(y!!, y, intArrayOf(2, 0, 1))
                val a2 = b.shuffle(x, x, intArrayOf(2, 0, 1))
                val b2 = b.shuffle(y, y, intArrayOf(1, 2, 0))
                fsub(fmul(a1, b1), fmul(a2, b2))
            }

            Intrinsic.Reflect -> {
                val d = dot(y!!, x!!)
                val scaled = fmul(splatScalar(type, fmul(d, constant(d.type, 2.0))), y)
                fsub(x, scaled)
            }

            Intrinsic.Refract -> {
                val eta = z!!
                val d = dot(y!!, x!!)
                val scalar = d.type
                val one = constant(scalar, 1.0)
                val k = fsub(one, fmul(fmul(eta, eta), fsub(one, fmul(d, d))))
                val negative = compare(Opcode.FLess, k, constant(scalar, 0.0))
                val factor = fadd(fmul(eta, d), call(Intrinsic.Sqrt, scalar, k))
                val result = fsub(fmul(splatScalar(type, eta), x), fmul(splatScalar(type, factor), y))
                select(negative, constant(type, 0.0), result)
            }

            Intrinsic.FaceForward -> {
                val d = dot(z!!, y!!)
                val negative = compare(Opcode.FLess, d, constant(d.type, 0.0))
                select(negative, x!!, b.unary(Opcode.FNeg, type, x))
            }

            Intrinsic.Transpose -> {
                val matrix = x!!.type as IrMatrix
                val result = type as IrMatrix
                val columns = List(result.columns) { column ->
                    b.construct(result.column, List(result.rows) { row -> b.extract(x, row, column) })
                }
                b.construct(result, columns)
                    .also { require(matrix.rows == result.columns) }
            }

            Intrinsic.Determinant -> determinant(x!!)
            Intrinsic.Ldexp -> {
                val exponent = b.unary(Opcode.SToF, type, y!!)
                fmul(x!!, call(Intrinsic.Exp2, type, exponent))
            }

            Intrinsic.FrexpMantissa, Intrinsic.FrexpExponent -> frexp(x!!, instruction.intrinsic == Intrinsic.FrexpMantissa)
            Intrinsic.Asinh -> call(Intrinsic.Log, type, fadd(x!!, call(Intrinsic.Sqrt, type, fadd(fmul(x, x), constant(type, 1.0)))))
            Intrinsic.Acosh -> call(Intrinsic.Log, type, fadd(x!!, call(Intrinsic.Sqrt, type, fsub(fmul(x, x), constant(type, 1.0)))))
            Intrinsic.Atanh -> fmul(
                constant(type, 0.5),
                call(Intrinsic.Log, type, fdiv(fadd(constant(type, 1.0), x!!), fsub(constant(type, 1.0), x))),
            )

            Intrinsic.Atan2 -> atan2(x!!, y!!)
            Intrinsic.Sinh -> fmul(fsub(call(Intrinsic.Exp, type, x!!), call(Intrinsic.Exp, type, b.unary(Opcode.FNeg, type, x))), constant(type, 0.5))
            Intrinsic.Cosh -> fmul(fadd(call(Intrinsic.Exp, type, x!!), call(Intrinsic.Exp, type, b.unary(Opcode.FNeg, type, x))), constant(type, 0.5))
            Intrinsic.Tanh -> {
                val twice = call(Intrinsic.Exp, type, fmul(x!!, constant(type, 2.0)))
                fdiv(fsub(twice, constant(type, 1.0)), fadd(twice, constant(type, 1.0)))
            }

            Intrinsic.Exp -> call(Intrinsic.Exp2, type, fmul(x!!, constant(type, 1.0 / ln(2.0))))
            Intrinsic.Log -> fmul(call(Intrinsic.Log2, type, x!!), constant(type, ln(2.0)))
            Intrinsic.Pow -> call(Intrinsic.Exp2, type, fmul(y!!, call(Intrinsic.Log2, type, x!!)))
            Intrinsic.Tan -> fdiv(call(Intrinsic.Sin, type, x!!), call(Intrinsic.Cos, type, x))
            Intrinsic.Rsqrt -> fdiv(constant(type, 1.0), call(Intrinsic.Sqrt, type, x!!))
            Intrinsic.FSign -> {
                val positive = select(compare(Opcode.FGreater, x!!, constant(type, 0.0)), constant(type, 1.0), constant(type, 0.0))
                select(compare(Opcode.FLess, x, constant(type, 0.0)), constant(type, -1.0), positive)
            }

            Intrinsic.SAbs -> select(compare(Opcode.SLess, x!!, integer(type, 0)), b.unary(Opcode.INeg, type, x), x)
            Intrinsic.FMin, Intrinsic.FMax, Intrinsic.SMin, Intrinsic.SMax, Intrinsic.UMin, Intrinsic.UMax -> {
                val comparison = when (instruction.intrinsic) {
                    Intrinsic.FMin -> Opcode.FLess
                    Intrinsic.FMax -> Opcode.FGreater
                    Intrinsic.SMin -> Opcode.SLess
                    Intrinsic.SMax -> Opcode.SGreater
                    Intrinsic.UMin -> Opcode.ULess
                    else -> Opcode.UGreater
                }
                select(compare(comparison, x!!, y!!), x, y)
            }

            Intrinsic.Fma -> fadd(fmul(x!!, y!!), z!!)
            Intrinsic.PackUnorm4x8, Intrinsic.PackSnorm4x8, Intrinsic.PackUnorm2x16, Intrinsic.PackSnorm2x16 -> pack(x!!)
            Intrinsic.UnpackUnorm4x8, Intrinsic.UnpackSnorm4x8, Intrinsic.UnpackUnorm2x16, Intrinsic.UnpackSnorm2x16 -> unpack(x!!)
            else -> null
        }
    }

    private fun atan2(y: Value, x: Value): Value {
        val type = y.type
        val zero = constant(type, 0.0)
        val base = call(Intrinsic.Atan, type, fdiv(y, x))
        val yNonNegative = compare(Opcode.FGreaterEqual, y, zero)
        val adjusted = select(yNonNegative, fadd(base, constant(type, PI)), fsub(base, constant(type, PI)))
        val negativeX = select(compare(Opcode.FLess, x, zero), adjusted, base)
        val vertical = select(
            compare(Opcode.FGreater, y, zero),
            constant(type, PI / 2),
            select(compare(Opcode.FLess, y, zero), constant(type, -PI / 2), zero),
        )
        return select(compare(Opcode.FEqual, x, zero), vertical, negativeX)
    }

    private fun determinant(matrix: Value): Value {
        val type = matrix.type as IrMatrix
        val scalar = type.column.element
        fun at(column: Int, row: Int): Value = b.extract(matrix, column, row)
        fun det2(a: Value, b2: Value, c: Value, d: Value): Value = fsub(fmul(a, d), fmul(b2, c))
        return when (type.columns) {
            2 -> det2(at(0, 0), at(1, 0), at(0, 1), at(1, 1))
            3 -> {
                val m = List(3) { c -> List(3) { r -> at(c, r) } }
                val first = fmul(m[0][0], fsub(fmul(m[1][1], m[2][2]), fmul(m[2][1], m[1][2])))
                val second = fmul(m[1][0], fsub(fmul(m[0][1], m[2][2]), fmul(m[2][1], m[0][2])))
                val third = fmul(m[2][0], fsub(fmul(m[0][1], m[1][2]), fmul(m[1][1], m[0][2])))
                fadd(fsub(first, second), third)
            }

            else -> {
                val m = List(4) { c -> List(4) { r -> at(c, r) } }
                fun minor(skipColumn: Int): Value {
                    val columns = (0 until 4).filter { it != skipColumn }
                    val rows = listOf(1, 2, 3)
                    val c = columns
                    val t0 = fmul(m[c[0]][rows[0]], fsub(fmul(m[c[1]][rows[1]], m[c[2]][rows[2]]), fmul(m[c[2]][rows[1]], m[c[1]][rows[2]])))
                    val t1 = fmul(m[c[1]][rows[0]], fsub(fmul(m[c[0]][rows[1]], m[c[2]][rows[2]]), fmul(m[c[2]][rows[1]], m[c[0]][rows[2]])))
                    val t2 = fmul(m[c[2]][rows[0]], fsub(fmul(m[c[0]][rows[1]], m[c[1]][rows[2]]), fmul(m[c[1]][rows[1]], m[c[0]][rows[2]])))
                    return fadd(fsub(t0, t1), t2)
                }
                var result: Value = Constants.scalar(scalar, 0.0)
                for (column in 0 until 4) {
                    val term = fmul(m[column][0], minor(column))
                    result = if (column % 2 == 0) fadd(result, term) else fsub(result, term)
                }
                result
            }
        }
    }

    private fun frexp(x: Value, mantissa: Boolean): Value {
        val source = x.type
        val bits = source.scalar.bits
        val mantissaBits = if (bits == 16) 10 else 23
        val bias = if (bits == 16) 14L else 126L
        val exponentMask = if (bits == 16) 0x1FL else 0xFFL
        val unsigned = intType(source)
        val raw = bitcast(x, unsigned)
        val isZero = compare(Opcode.FEqual, x, constant(source, 0.0))
        if (mantissa) {
            val clearMask = integer(unsigned, ((exponentMask shl mantissaBits).inv()) and ((1L shl bits) - 1))
            val halfExponent = integer(unsigned, bias shl mantissaBits)
            val value = bitcast(or(and(raw, clearMask), halfExponent), source)
            return select(isZero, x, value)
        }
        val signed = instruction.type
        val exponent = and(op(Opcode.LShr, unsigned, raw, integer(unsigned, mantissaBits.toLong())), integer(unsigned, exponentMask))
        val converted = if (signed.scalar.bits == unsigned.scalar.bits) {
            bitcast(exponent, signed)
        } else {
            b.unary(Opcode.UConvert, signed, exponent)
        }
        return select(isZero, integer(signed, 0), isub(converted, integer(signed, bias)))
    }

    private fun pack(x: Value): Value {
        val intrinsic = instruction.intrinsic!!
        val vector = x.type as IrVector
        val lanes = vector.count
        val laneBits = 32 / lanes
        val signed = intrinsic == Intrinsic.PackSnorm4x8 || intrinsic == Intrinsic.PackSnorm2x16
        val scale = if (signed) ((1L shl (laneBits - 1)) - 1).toDouble() else ((1L shl laneBits) - 1).toDouble()
        val low = if (signed) -1.0 else 0.0
        val clamped = call(Intrinsic.FClamp, vector, x, constant(vector, low), constant(vector, 1.0))
        val rounded = call(Intrinsic.Rint, vector, fmul(clamped, constant(vector, scale)))
        val intVector = IrVector.of(IrInt.I32, lanes)
        val converted = b.unary(Opcode.FToS, intVector, rounded)
        var result: Value = ConstantScalar.u32(0)
        val mask = (1L shl laneBits) - 1
        for (lane in 0 until lanes) {
            val component = bitcast(b.extract(converted, lane), IrInt.U32)
            val masked = and(component, ConstantScalar.int(IrInt.U32, mask))
            val shifted = if (lane == 0) masked else op(Opcode.Shl, IrInt.U32, masked, ConstantScalar.u32(lane * laneBits))
            result = or(result, shifted)
        }
        return result
    }

    private fun unpack(x: Value): Value {
        val intrinsic = instruction.intrinsic!!
        val vector = type as IrVector
        val lanes = vector.count
        val laneBits = 32 / lanes
        val signed = intrinsic == Intrinsic.UnpackSnorm4x8 || intrinsic == Intrinsic.UnpackSnorm2x16
        val components = List(lanes) { lane ->
            val shift = 32 - laneBits * (lane + 1)
            val raw = if (signed) {
                val asSigned = bitcast(x, IrInt.I32)
                val left = op(Opcode.Shl, IrInt.I32, asSigned, ConstantScalar.i32(shift))
                op(Opcode.AShr, IrInt.I32, left, ConstantScalar.i32(32 - laneBits))
            } else {
                val left = op(Opcode.Shl, IrInt.U32, x, ConstantScalar.u32(shift))
                op(Opcode.LShr, IrInt.U32, left, ConstantScalar.u32(32 - laneBits))
            }
            b.unary(if (signed) Opcode.SToF else Opcode.UToF, IrFloat.F32, raw)
        }
        val scale = if (signed) ((1L shl (laneBits - 1)) - 1).toDouble() else ((1L shl laneBits) - 1).toDouble()
        val floats = fdiv(b.construct(vector, components), constant(vector, scale))
        return if (signed) call(Intrinsic.FMax, vector, floats, constant(vector, -1.0)) else floats
    }
}
