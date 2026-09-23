package gg.sona.msl.sema

import gg.sona.msl.types.Type

class BuiltinParameter(val kind: PatternKind, val fixed: Type? = null) {
    companion object {
        val T = BuiltinParameter(PatternKind.T)
        val Scalar = BuiltinParameter(PatternKind.ScalarOfT)
        val BoolOfT = BuiltinParameter(PatternKind.BoolOfT)
        val IntOfT = BuiltinParameter(PatternKind.IntOfT)
        val UIntOfT = BuiltinParameter(PatternKind.UIntOfT)
        val ReferenceToT = BuiltinParameter(PatternKind.ReferenceToT)
        val ReferenceToIntOfT = BuiltinParameter(PatternKind.ReferenceToIntOfT)
        val Transposed = BuiltinParameter(PatternKind.TransposedT)

        fun fixed(type: Type) = BuiltinParameter(PatternKind.Fixed, type)
    }
}
