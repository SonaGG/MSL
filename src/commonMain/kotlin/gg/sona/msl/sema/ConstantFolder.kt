package gg.sona.msl.sema

import gg.sona.msl.hir.CompositeConstant
import gg.sona.msl.hir.ConstValue
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.GlobalVariable
import gg.sona.msl.hir.HBinary
import gg.sona.msl.hir.HBinaryOperator
import gg.sona.msl.hir.HBitcast
import gg.sona.msl.hir.HComma
import gg.sona.msl.hir.HConditional
import gg.sona.msl.hir.HConstruct
import gg.sona.msl.hir.HConvert
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HIndex
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.HLogical
import gg.sona.msl.hir.HMember
import gg.sona.msl.hir.HSwizzle
import gg.sona.msl.hir.HUnary
import gg.sona.msl.hir.HUnaryOperator
import gg.sona.msl.hir.HVariableRef
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.Variable
import gg.sona.msl.hir.ZeroConstant
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.util.HalfFloat

class ConstantFolder(private val localValues: Map<Variable, ConstValue>) {
    fun fold(expression: HExpr): ConstValue? = try {
        evaluate(expression)
    } catch (_: ArithmeticException) {
        null
    }

    private fun evaluate(expression: HExpr): ConstValue? = when (expression) {
        is HLiteral -> expression.value
        is HVariableRef -> {
            val variable = expression.variable
            if (variable is GlobalVariable) {
                if (variable.isFunctionConstant) null else variable.constantValue
            } else {
                localValues[variable]
            }
        }

        is HConvert -> evaluate(expression.operand)?.let { convert(it, expression.type) }
        is HBitcast -> evaluate(expression.operand)?.let { bitcast(it, expression.type) }
        is HUnary -> evaluate(expression.operand)?.let { unary(expression.operator, it, expression.type) }
        is HBinary -> {
            val left = evaluate(expression.left)
            val right = evaluate(expression.right)
            if (left == null || right == null) null else binary(expression.operator, left, right, expression.type)
        }

        is HLogical -> {
            val left = evaluate(expression.left) as? ScalarConstant
            if (left == null) {
                null
            } else if (expression.isAnd && !left.asBoolean) {
                ScalarConstant.of(ScalarType.Bool, false)
            } else if (!expression.isAnd && left.asBoolean) {
                ScalarConstant.of(ScalarType.Bool, true)
            } else {
                (evaluate(expression.right) as? ScalarConstant)?.let { ScalarConstant.of(ScalarType.Bool, it.asBoolean) }
            }
        }

        is HConditional -> {
            val condition = evaluate(expression.condition) as? ScalarConstant
            when {
                condition == null -> null
                condition.asBoolean -> evaluate(expression.whenTrue)
                else -> evaluate(expression.whenFalse)
            }
        }

        is HConstruct -> construct(expression)
        is HSwizzle -> {
            val base = evaluate(expression.base)
            if (base == null) {
                null
            } else {
                val lanes = components(base)
                if (expression.components.size == 1) {
                    lanes[expression.components[0]]
                } else {
                    CompositeConstant(expression.type, expression.components.map { lanes[it] })
                }
            }
        }

        is HMember -> evaluate(expression.base)?.let { element(it, expression.member.index) }
        is HIndex -> {
            val base = evaluate(expression.base)
            val index = evaluate(expression.index) as? ScalarConstant
            if (base == null || index == null) null else element(base, index.asLong.toInt())
        }

        is HComma -> evaluate(expression.right)
        else -> null
    }

    fun element(value: ConstValue, index: Int): ConstValue? {
        val type = value.type
        return when (value) {
            is CompositeConstant -> value.elements.getOrNull(index)
            is ZeroConstant -> elementType(type, index)?.let { zero(it) }
            else -> null
        }
    }

    private fun elementType(type: Type, index: Int): Type? = when (type) {
        is VectorType -> type.element
        is MatrixType -> type.columnType
        is ArrayType -> type.element
        is StructType -> type.fields.getOrNull(index)?.type
        else -> null
    }

    fun zero(type: Type): ConstValue = when (type) {
        is ScalarType -> ScalarConstant.of(type, 0L)
        is EnumType -> EnumConstant(type, 0)
        else -> ZeroConstant(type)
    }

    fun components(value: ConstValue): List<ScalarConstant> = when {
        value is ScalarConstant -> listOf(value)
        value is ZeroConstant && value.type is VectorType -> {
            val vector = value.type as VectorType
            List(vector.size) { ScalarConstant.of(vector.element, 0L) }
        }

        value is CompositeConstant && value.type is VectorType -> value.elements.map { it as ScalarConstant }
        value is CompositeConstant && value.type is MatrixType -> value.elements.flatMap { components(it) }
        value is ZeroConstant && value.type is MatrixType -> {
            val matrix = value.type as MatrixType
            List(matrix.columns * matrix.rows) { ScalarConstant.of(matrix.element, 0L) }
        }

        else -> emptyList()
    }

    private fun construct(expression: HConstruct): ConstValue? {
        val arguments = expression.arguments.map { evaluate(it) ?: return null }
        return when (val type = expression.type) {
            is VectorType -> {
                if (arguments.isEmpty()) return ZeroConstant(type)
                val lanes = arguments.flatMap { components(it) }
                val scalars = if (lanes.size == 1) List(type.size) { lanes[0] } else lanes
                if (scalars.size != type.size) return null
                CompositeConstant(type.unpacked(), scalars.map { it.convertTo(type.element) })
            }

            is ScalarType -> arguments.singleOrNull()?.let { convert(it, type) } ?: ScalarConstant.of(type, 0L)
            is MatrixType -> {
                val diagonal = arguments.singleOrNull() as? ScalarConstant
                when {
                    arguments.isEmpty() -> ZeroConstant(type)
                    diagonal != null -> {
                        val value = diagonal.convertTo(type.element)
                        val zero = ScalarConstant.of(type.element, 0L)
                        CompositeConstant(
                            type,
                            List(type.columns) { column ->
                                CompositeConstant(type.columnType, List(type.rows) { row -> if (row == column) value else zero })
                            },
                        )
                    }

                    else -> CompositeConstant(type, arguments)
                }
            }

            else -> if (arguments.isEmpty()) ZeroConstant(type) else CompositeConstant(type, arguments)
        }
    }

    fun convert(value: ConstValue, to: Type): ConstValue? {
        val target = ConversionRules.valueType(to)
        return when {
            value.type == target -> value
            value is ScalarConstant && target is ScalarType -> value.convertTo(target)
            value is ScalarConstant && target is EnumType -> EnumConstant(target, value.asLong)
            value is EnumConstant && target is ScalarType -> ScalarConstant.of(target, value.value)
            value is EnumConstant && target is EnumType -> EnumConstant(target, value.value)
            value is ScalarConstant && target is VectorType -> {
                val lane = value.convertTo(target.element)
                CompositeConstant(target, List(target.size) { lane })
            }

            target is VectorType -> {
                val lanes = components(value)
                if (lanes.size != target.size) null else CompositeConstant(target, lanes.map { it.convertTo(target.element) })
            }

            value is ZeroConstant -> zero(target)
            else -> null
        }
    }

    private fun bitcast(value: ConstValue, to: Type): ConstValue? {
        val source = value as? ScalarConstant ?: return null
        val target = to as? ScalarType ?: return null
        if (source.kind.bits != target.kind.bits) return null
        val raw = rawBits(source)
        return fromRawBits(target, raw)
    }

    private fun rawBits(value: ScalarConstant): Long = when (value.kind) {
        ScalarKind.Float -> Double.fromBits(value.bits).toFloat().toRawBits().toLong() and 0xFFFFFFFFL
        ScalarKind.Half -> HalfFloat.fromFloat(Double.fromBits(value.bits).toFloat()).toLong()
        else -> value.bits
    }

    private fun fromRawBits(type: ScalarType, raw: Long): ScalarConstant = when (type.kind) {
        ScalarKind.Float -> ScalarConstant.of(type, Float.fromBits(raw.toInt()).toDouble())
        ScalarKind.Half -> ScalarConstant.of(type, HalfFloat.toFloat(raw.toInt()).toDouble())
        else -> ScalarConstant.of(type, raw)
    }

    private fun unary(operator: HUnaryOperator, value: ConstValue, type: Type): ConstValue? {
        if (value is CompositeConstant || value is ZeroConstant) {
            val vector = type as? VectorType ?: return null
            val lanes = components(value).map { unary(operator, it, vector.element) as? ScalarConstant ?: return null }
            return CompositeConstant(vector, lanes)
        }
        val scalar = value as? ScalarConstant ?: return null
        val target = type as? ScalarType ?: return null
        return when (operator) {
            HUnaryOperator.Negate -> if (target.kind.isFloat) {
                ScalarConstant.of(target, -scalar.asDouble)
            } else {
                ScalarConstant.of(target, -scalar.bits)
            }

            HUnaryOperator.LogicalNot -> ScalarConstant.of(ScalarType.Bool, !scalar.asBoolean)
            HUnaryOperator.BitwiseNot -> ScalarConstant.of(target, scalar.bits.inv())
        }
    }

    private fun binary(operator: HBinaryOperator, left: ConstValue, right: ConstValue, type: Type): ConstValue? {
        if (left is EnumConstant || right is EnumConstant) {
            val leftValue = (left as? EnumConstant)?.value ?: (left as? ScalarConstant)?.asLong ?: return null
            val rightValue = (right as? EnumConstant)?.value ?: (right as? ScalarConstant)?.asLong ?: return null
            val result = when (operator) {
                HBinaryOperator.BitwiseOr -> leftValue or rightValue
                HBinaryOperator.BitwiseAnd -> leftValue and rightValue
                HBinaryOperator.BitwiseXor -> leftValue xor rightValue
                HBinaryOperator.Equal -> return ScalarConstant.of(ScalarType.Bool, leftValue == rightValue)
                HBinaryOperator.NotEqual -> return ScalarConstant.of(ScalarType.Bool, leftValue != rightValue)
                else -> return null
            }
            return if (type is EnumType) EnumConstant(type, result) else (type as? ScalarType)?.let { ScalarConstant.of(it, result) }
        }
        if (type is VectorType) {
            val leftLanes = components(left)
            val rightLanes = components(right)
            val lanes = List(type.size) { index ->
                val a = leftLanes.getOrNull(index) ?: leftLanes.singleOrNull() ?: return null
                val b = rightLanes.getOrNull(index) ?: rightLanes.singleOrNull() ?: return null
                scalarBinary(operator, a, b, type.element) ?: return null
            }
            return CompositeConstant(type, lanes)
        }
        val a = left as? ScalarConstant ?: return null
        val b = right as? ScalarConstant ?: return null
        val target = type as? ScalarType ?: return null
        return scalarBinary(operator, a, b, target)
    }

    private fun scalarBinary(operator: HBinaryOperator, a: ScalarConstant, b: ScalarConstant, type: ScalarType): ScalarConstant? {
        val operandKind = a.kind
        if (operator.isComparison) {
            val result = if (operandKind.isFloat) {
                val x = a.asDouble
                val y = b.asDouble
                when (operator) {
                    HBinaryOperator.Equal -> x == y
                    HBinaryOperator.NotEqual -> x != y
                    HBinaryOperator.Less -> x < y
                    HBinaryOperator.LessEqual -> x <= y
                    HBinaryOperator.Greater -> x > y
                    else -> x >= y
                }
            } else {
                val comparison = if (operandKind.isSigned || operandKind.isBool) {
                    a.bits.compareTo(b.bits)
                } else {
                    a.bits.toULong().compareTo(b.bits.toULong())
                }
                when (operator) {
                    HBinaryOperator.Equal -> comparison == 0
                    HBinaryOperator.NotEqual -> comparison != 0
                    HBinaryOperator.Less -> comparison < 0
                    HBinaryOperator.LessEqual -> comparison <= 0
                    HBinaryOperator.Greater -> comparison > 0
                    else -> comparison >= 0
                }
            }
            return ScalarConstant.of(ScalarType.Bool, result)
        }
        if (type.kind.isFloat) {
            val x = a.asDouble
            val y = b.asDouble
            val result = when (operator) {
                HBinaryOperator.Add -> x + y
                HBinaryOperator.Subtract -> x - y
                HBinaryOperator.Multiply -> x * y
                HBinaryOperator.Divide -> x / y
                else -> return null
            }
            return ScalarConstant.of(type, result)
        }
        val x = a.bits
        val y = b.bits
        val signed = type.kind.isSigned
        val result = when (operator) {
            HBinaryOperator.Add -> x + y
            HBinaryOperator.Subtract -> x - y
            HBinaryOperator.Multiply -> x * y
            HBinaryOperator.Divide -> if (y == 0L) return null else if (signed) x / y else (x.toULong() / y.toULong()).toLong()
            HBinaryOperator.Remainder -> if (y == 0L) return null else if (signed) x % y else (x.toULong() % y.toULong()).toLong()
            HBinaryOperator.ShiftLeft -> x shl y.toInt()
            HBinaryOperator.ShiftRight -> if (signed) x shr y.toInt() else x ushr y.toInt()
            HBinaryOperator.BitwiseAnd -> x and y
            HBinaryOperator.BitwiseOr -> x or y
            HBinaryOperator.BitwiseXor -> x xor y
            else -> return null
        }
        return ScalarConstant.of(type, result)
    }
}
