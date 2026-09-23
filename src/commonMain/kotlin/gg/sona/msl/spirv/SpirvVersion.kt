package gg.sona.msl.spirv

enum class SpirvVersion(val major: Int, val minor: Int) {
    V1_3(1, 3),
    V1_4(1, 4),
    V1_5(1, 5),
    V1_6(1, 6),
    ;

    val word: Int
        get() = (major shl 16) or (minor shl 8)

    fun atLeast(other: SpirvVersion): Boolean = ordinal >= other.ordinal
}
