package gg.sona.msl.ast

import kotlin.jvm.JvmInline

@JvmInline
value class DeclSpecifiers(val bits: Int) {
    val isStatic: Boolean
        get() = bits and STATIC != 0

    val isInline: Boolean
        get() = bits and INLINE != 0

    val isConstexpr: Boolean
        get() = bits and CONSTEXPR != 0

    val isExtern: Boolean
        get() = bits and EXTERN != 0

    val isTypedef: Boolean
        get() = bits and TYPEDEF != 0

    operator fun plus(other: DeclSpecifiers): DeclSpecifiers = DeclSpecifiers(bits or other.bits)

    operator fun contains(other: DeclSpecifiers): Boolean = bits and other.bits == other.bits

    companion object {
        private const val STATIC = 1
        private const val INLINE = 2
        private const val CONSTEXPR = 4
        private const val EXTERN = 8
        private const val TYPEDEF = 16

        val None = DeclSpecifiers(0)
        val Static = DeclSpecifiers(STATIC)
        val Inline = DeclSpecifiers(INLINE)
        val Constexpr = DeclSpecifiers(CONSTEXPR)
        val Extern = DeclSpecifiers(EXTERN)
        val Typedef = DeclSpecifiers(TYPEDEF)
    }
}
