package gg.sona.msl.sema

import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.HConvert
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.ErrorType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.NullPointerType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType

object ConversionRules {
    const val EXACT = 0
    const val PROMOTION = 1
    const val CONVERSION = 2
    const val SPLAT = 3

    fun valueType(type: Type): Type = when (type) {
        is VectorType -> if (type.packed) type.unpacked() else type
        else -> type
    }

    fun promote(kind: ScalarKind): ScalarKind = when (kind) {
        ScalarKind.Bool, ScalarKind.Char, ScalarKind.UChar, ScalarKind.Short, ScalarKind.UShort -> ScalarKind.Int
        else -> kind
    }

    fun usualArithmetic(a: ScalarKind, b: ScalarKind): ScalarKind {
        if (a.isFloat || b.isFloat) {
            if (a.isFloat && b.isFloat) return if (a.rank >= b.rank) a else b
            return if (a.isFloat) a else b
        }
        val left = promote(a)
        val right = promote(b)
        if (left == right) return left
        if (left.isSigned == right.isSigned) return if (left.rank >= right.rank) left else right
        val unsigned = if (left.isSigned) right else left
        val signed = if (left.isSigned) left else right
        if (unsigned.rank >= signed.rank) return unsigned
        if (signed.bits > unsigned.bits) return signed
        return signed.toUnsigned()
    }

    fun scalarOf(type: Type): ScalarType? = when (type) {
        is ScalarType -> type
        is VectorType -> type.element
        is EnumType -> type.underlying
        else -> null
    }

    fun withElement(type: Type, element: ScalarType): Type = when (type) {
        is VectorType -> VectorType.of(element, type.size)
        is MatrixType -> MatrixType.of(element, type.columns, type.rows)
        else -> element
    }

    fun sameValueType(a: Type, b: Type): Boolean = valueType(a) == valueType(b)

    fun implicitCost(from: Type, to: Type, isNullLiteral: Boolean = false): Int? {
        val source = valueType(from)
        val target = valueType(to)
        if (source == target || source == ErrorType || target == ErrorType) return EXACT
        if (isNullLiteral && target is PointerType) return CONVERSION
        when {
            source is NullPointerType && target is PointerType -> return CONVERSION
            source is ScalarType && target is ScalarType -> {
                if (source.kind == target.kind) return EXACT
                if (target.kind.isBool) return CONVERSION
                if (promote(source.kind) == target.kind) return PROMOTION
                if (source.kind == ScalarKind.Half && target.kind == ScalarKind.Float) return PROMOTION
                return CONVERSION
            }

            source is EnumType && target is ScalarType -> return if (source.isScoped) null else CONVERSION
            source is ScalarType && target is VectorType -> return SPLAT
            source is PointerType && target is PointerType -> {
                if (source.addressSpace != target.addressSpace) return null
                if (!sameValueType(source.pointee, target.pointee)) return null
                if (source.isConstPointee && !target.isConstPointee) return null
                return PROMOTION
            }

            source is ArrayType && target is PointerType -> {
                return if (sameValueType(source.element, target.pointee)) CONVERSION else null
            }

            else -> return null
        }
    }

    fun explicitlyConvertible(from: Type, to: Type): Boolean {
        val source = valueType(from)
        val target = valueType(to)
        if (implicitCost(source, target) != null) return true
        return when {
            source is VectorType && target is VectorType -> source.size == target.size
            source is EnumType && target is EnumType -> true
            source is ScalarType && target is EnumType -> true
            source is EnumType && target is VectorType -> true
            source is PointerType && target is PointerType -> source.addressSpace == target.addressSpace
            source is MatrixType && target is MatrixType ->
                source.columns == target.columns && source.rows == target.rows
            else -> false
        }
    }

    fun convert(expression: HExpr, to: Type): HExpr {
        val target = valueType(to)
        val sourceType = valueType(expression.type)
        if (sourceType == target || sourceType == ErrorType || target == ErrorType) return expression
        if (expression is HLiteral) {
            val value = expression.value
            if (value is ScalarConstant && target is ScalarType) {
                return HLiteral(value.convertTo(target), expression.location)
            }
            if (value is ScalarConstant && target is EnumType) {
                return HLiteral(EnumConstant(target, value.asLong), expression.location)
            }
            if (value is EnumConstant && target is ScalarType) {
                return HLiteral(ScalarConstant.of(target, value.value), expression.location)
            }
        }
        return HConvert(expression, target, expression.location)
    }
}
