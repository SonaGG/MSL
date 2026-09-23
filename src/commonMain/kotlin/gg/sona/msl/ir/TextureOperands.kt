package gg.sona.msl.ir

import kotlin.jvm.JvmInline

@JvmInline
value class TextureOperands(val bits: Int) {
    operator fun contains(flag: TextureOperands): Boolean = bits and flag.bits != 0

    operator fun plus(flag: TextureOperands): TextureOperands = TextureOperands(bits or flag.bits)

    override fun toString(): String = NAMES.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.joinToString("|")

    companion object {
        private val NAMES = listOf("sampler", "array", "bias", "lod", "grad", "minlod", "offset", "compare", "sample", "value")

        val None = TextureOperands(0)
        val Sampler = TextureOperands(1)
        val ArrayIndex = TextureOperands(2)
        val Bias = TextureOperands(4)
        val Lod = TextureOperands(8)
        val Gradient = TextureOperands(16)
        val MinLod = TextureOperands(32)
        val Offset = TextureOperands(64)
        val Compare = TextureOperands(128)
        val SampleIndex = TextureOperands(256)
        val Texel = TextureOperands(512)
    }
}
