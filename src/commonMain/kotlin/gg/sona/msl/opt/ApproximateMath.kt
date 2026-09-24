package gg.sona.msl.opt

import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

class ApproximateMath(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    private lateinit var builder: IrBuilder
    private lateinit var type: IrType

    fun run(function: IrFunction): Boolean {
        builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (instruction in function.instructions().toList()) {
            if (instruction.opcode != Opcode.Intrinsic) continue
            val scalar = (instruction.type as? IrFloat) ?: ((instruction.type as? IrVector)?.element as? IrFloat) ?: continue
            if (scalar.bits != 32) continue
            type = instruction.type
            builder.positionBefore(instruction)
            val x = instruction.operands.getOrNull(0) ?: continue
            val replacement = when (instruction.intrinsic) {
                Intrinsic.Atan -> atan(x)
                Intrinsic.Atan2 -> atan2(x, instruction.operands[1])
                Intrinsic.Acos -> if (native(Intrinsic.Sqrt, instruction)) acos(x) else null
                Intrinsic.Asin -> if (native(Intrinsic.Sqrt, instruction)) sub(constant(HALF_PI), acos(x)) else null
                Intrinsic.Tanh -> if (native(Intrinsic.Exp2, instruction)) tanh(x) else null
                Intrinsic.Sinh, Intrinsic.Cosh -> if (native(Intrinsic.Exp2, instruction)) hyperbolic(x, instruction.intrinsic == Intrinsic.Sinh) else null
                else -> null
            } ?: continue
            replacements[instruction] = replacement
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun native(intrinsic: Intrinsic, context: Instruction): Boolean = isNative(intrinsic, context)

    private fun constant(value: Double): Value = Constants.splat(type, Constants.scalar(type.scalar, value))

    private fun mul(a: Value, b: Value): Value = builder.binary(Opcode.FMul, type, a, b)

    private fun add(a: Value, b: Value): Value = builder.binary(Opcode.FAdd, type, a, b)

    private fun sub(a: Value, b: Value): Value = builder.binary(Opcode.FSub, type, a, b)

    private fun div(a: Value, b: Value): Value = builder.binary(Opcode.FDiv, type, a, b)

    private fun call(intrinsic: Intrinsic, vararg operands: Value): Value = builder.intrinsic(intrinsic, type, operands.toList())

    private fun compare(opcode: Opcode, a: Value, b: Value): Value =
        builder.binary(opcode, if (type is IrVector) IrVector.of(IrBool, (type as IrVector).count) else IrBool, a, b)

    private fun select(condition: Value, whenTrue: Value, whenFalse: Value): Value = builder.select(condition, whenTrue, whenFalse)

    private fun polynomial(x: Value, coefficients: DoubleArray): Value {
        var result = constant(coefficients.last())
        for (i in coefficients.size - 2 downTo 0) result = add(mul(result, x), constant(coefficients[i]))
        return result
    }

    private fun atanUnit(t: Value): Value = mul(t, polynomial(mul(t, t), ATAN))

    private fun atan(x: Value): Value {
        val magnitude = call(Intrinsic.FAbs, x)
        val large = compare(Opcode.FGreater, magnitude, constant(1.0))
        val reduced = select(large, div(constant(1.0), magnitude), magnitude)
        val angle = atanUnit(reduced)
        val unsigned = select(large, sub(constant(HALF_PI), angle), angle)
        return select(compare(Opcode.FLess, x, constant(0.0)), builder.unary(Opcode.FNeg, type, unsigned), unsigned)
    }

    private fun atan2(y: Value, x: Value): Value {
        val ax = call(Intrinsic.FAbs, x)
        val ay = call(Intrinsic.FAbs, y)
        val high = call(Intrinsic.FMax, ax, ay)
        val low = call(Intrinsic.FMin, ax, ay)
        val angle = atanUnit(div(low, select(compare(Opcode.FEqual, high, constant(0.0)), constant(1.0), high)))
        val steep = select(compare(Opcode.FGreater, ay, ax), sub(constant(HALF_PI), angle), angle)
        val left = select(compare(Opcode.FLess, x, constant(0.0)), sub(constant(PI), steep), steep)
        return select(compare(Opcode.FLess, y, constant(0.0)), builder.unary(Opcode.FNeg, type, left), left)
    }

    private fun acos(x: Value): Value {
        val magnitude = call(Intrinsic.FAbs, x)
        val root = call(Intrinsic.Sqrt, call(Intrinsic.FMax, sub(constant(1.0), magnitude), constant(0.0)))
        val positive = mul(root, polynomial(magnitude, ACOS))
        return select(compare(Opcode.FLess, x, constant(0.0)), sub(constant(PI), positive), positive)
    }

    private fun tanh(x: Value): Value {
        val clamped = call(Intrinsic.FMin, call(Intrinsic.FMax, x, constant(-TANH_LIMIT)), constant(TANH_LIMIT))
        val exponential = call(Intrinsic.Exp2, mul(clamped, constant(2.0 * LOG2_E)))
        return sub(constant(1.0), div(constant(2.0), add(exponential, constant(1.0))))
    }

    private fun hyperbolic(x: Value, odd: Boolean): Value {
        val positive = call(Intrinsic.Exp2, mul(x, constant(LOG2_E)))
        val negative = div(constant(1.0), positive)
        return mul(if (odd) sub(positive, negative) else add(positive, negative), constant(0.5))
    }

    private companion object {
        const val PI = 3.141592653589793
        const val HALF_PI = 1.5707963267948966
        const val LOG2_E = 1.4426950408889634
        const val TANH_LIMIT = 9.0
        val ATAN = doubleArrayOf(0.9998660, -0.3302995, 0.1801410, -0.0851330, 0.0208351)
        val ACOS = doubleArrayOf(1.5707288, -0.2121144, 0.0742610, -0.0187293)
    }
}
