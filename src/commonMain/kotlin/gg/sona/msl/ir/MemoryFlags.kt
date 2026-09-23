package gg.sona.msl.ir

import kotlin.jvm.JvmInline

@JvmInline
value class MemoryFlags(val bits: Int) {
    val device: Boolean
        get() = bits and 1 != 0

    val threadgroup: Boolean
        get() = bits and 2 != 0

    val texture: Boolean
        get() = bits and 4 != 0

    override fun toString(): String = listOfNotNull(
        "device".takeIf { device },
        "threadgroup".takeIf { threadgroup },
        "texture".takeIf { texture },
    ).joinToString("|").ifEmpty { "none" }
}
