package gg.sona.msl.types

sealed class Type {
    open val isScalar: Boolean
        get() = false

    open val isVector: Boolean
        get() = false

    open val scalarKind: ScalarKind?
        get() = null

    val isArithmetic: Boolean
        get() = (this is ScalarType && !scalarKind.isBool) || (this is VectorType && !scalarKind.isBool)

    val isNumericScalarOrVector: Boolean
        get() = (this is ScalarType || this is VectorType) && scalarKind != ScalarKind.Bool

    val isBoolScalarOrVector: Boolean
        get() = (this is ScalarType || this is VectorType) && scalarKind == ScalarKind.Bool

    val isFloatScalarOrVector: Boolean
        get() = (this is ScalarType || this is VectorType) && scalarKind?.isFloat == true

    val isIntegerScalarOrVector: Boolean
        get() = (this is ScalarType || this is VectorType) && scalarKind?.isInteger == true

    val componentCount: Int
        get() = when (this) {
            is VectorType -> size
            else -> 1
        }

    val isOpaque: Boolean
        get() = this is TextureType || this is SamplerType
}
