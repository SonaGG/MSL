package gg.sona.msl.types

enum class ScalarKind(
    val spelling: String,
    val bits: Int,
    val isSigned: Boolean,
    val isFloat: Boolean,
    val rank: Int,
) {
    Bool("bool", 8, false, false, 0),
    Char("char", 8, true, false, 1),
    UChar("uchar", 8, false, false, 1),
    Short("short", 16, true, false, 2),
    UShort("ushort", 16, false, false, 2),
    Int("int", 32, true, false, 3),
    UInt("uint", 32, false, false, 3),
    Long("long", 64, true, false, 4),
    ULong("ulong", 64, false, false, 4),
    Half("half", 16, true, true, 5),
    BFloat("bfloat", 16, true, true, 5),
    Float("float", 32, true, true, 6),
    ;

    val isInteger: Boolean
        get() = !isFloat && this != Bool

    val isBool: Boolean
        get() = this == Bool

    val byteSize: Int
        get() = bits / 8

    fun toUnsigned(): ScalarKind = when (this) {
        Char -> UChar
        Short -> UShort
        Int -> UInt
        Long -> ULong
        else -> this
    }

    fun toSigned(): ScalarKind = when (this) {
        UChar -> Char
        UShort -> Short
        UInt -> Int
        ULong -> Long
        else -> this
    }

    companion object {
        fun integer(bits: Int, signed: Boolean): ScalarKind =
            entries.first { it.isInteger && it.bits == bits && it.isSigned == signed }
    }
}
