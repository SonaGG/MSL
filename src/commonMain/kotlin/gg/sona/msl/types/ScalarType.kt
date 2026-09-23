package gg.sona.msl.types

class ScalarType private constructor(val kind: ScalarKind) : Type() {
    override val isScalar: Boolean
        get() = true

    override val scalarKind: ScalarKind
        get() = kind

    override fun toString(): String = kind.spelling

    companion object {
        private val instances = ScalarKind.entries.map { ScalarType(it) }

        fun of(kind: ScalarKind): ScalarType = instances[kind.ordinal]

        val Bool = of(ScalarKind.Bool)
        val Char = of(ScalarKind.Char)
        val UChar = of(ScalarKind.UChar)
        val Short = of(ScalarKind.Short)
        val UShort = of(ScalarKind.UShort)
        val Int = of(ScalarKind.Int)
        val UInt = of(ScalarKind.UInt)
        val Long = of(ScalarKind.Long)
        val ULong = of(ScalarKind.ULong)
        val Half = of(ScalarKind.Half)
        val Float = of(ScalarKind.Float)
    }
}
