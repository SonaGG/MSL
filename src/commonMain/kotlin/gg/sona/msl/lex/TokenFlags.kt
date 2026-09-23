package gg.sona.msl.lex

import kotlin.jvm.JvmInline

@JvmInline
value class TokenFlags(val bits: Int) {
    val startOfLine: Boolean
        get() = bits and START_OF_LINE != 0

    val leadingSpace: Boolean
        get() = bits and LEADING_SPACE != 0

    val noExpand: Boolean
        get() = bits and NO_EXPAND != 0

    operator fun plus(other: TokenFlags): TokenFlags = TokenFlags(bits or other.bits)

    operator fun minus(other: TokenFlags): TokenFlags = TokenFlags(bits and other.bits.inv())

    operator fun contains(other: TokenFlags): Boolean = bits and other.bits == other.bits

    companion object {
        private const val START_OF_LINE = 1
        private const val LEADING_SPACE = 2
        private const val NO_EXPAND = 4

        val None = TokenFlags(0)
        val StartOfLine = TokenFlags(START_OF_LINE)
        val LeadingSpace = TokenFlags(LEADING_SPACE)
        val NoExpand = TokenFlags(NO_EXPAND)
    }
}
