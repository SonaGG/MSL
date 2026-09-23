package gg.sona.msl.sema

import gg.sona.msl.types.ScalarKind

enum class ElementClass {
    Float,
    Integer,
    Numeric,
    Bool,
    Any,
    ;

    fun accepts(kind: ScalarKind): Boolean = when (this) {
        Float -> kind.isFloat
        Integer -> kind.isInteger
        Numeric -> !kind.isBool
        Bool -> kind.isBool
        Any -> true
    }
}
