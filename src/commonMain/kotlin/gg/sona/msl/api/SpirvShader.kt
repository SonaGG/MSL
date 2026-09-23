package gg.sona.msl.api

import gg.sona.msl.reflect.ShaderReflection
import gg.sona.msl.util.ByteSink

class SpirvShader(val words: IntArray, val reflection: ShaderReflection) {
    fun toByteArray(): ByteArray {
        val sink = ByteSink(words.size * 4)
        for (word in words) sink.writeIntLe(word)
        return sink.toByteArray()
    }
}
