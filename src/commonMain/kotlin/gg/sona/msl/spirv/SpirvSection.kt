package gg.sona.msl.spirv

import gg.sona.msl.util.IntList

class SpirvSection {
    val words = IntList(256)

    fun instruction(opcode: Int, vararg operands: Int) {
        words.add((operands.size + 1) shl 16 or opcode)
        for (operand in operands) words.add(operand)
    }

    fun instruction(opcode: Int, operands: IntList) {
        words.add((operands.size + 1) shl 16 or opcode)
        words.addAll(operands)
    }

    fun instructionWithString(opcode: Int, prefix: IntArray, text: String, suffix: IntArray = IntArray(0)) {
        val encoded = encode(text)
        words.add((prefix.size + encoded.size + suffix.size + 1) shl 16 or opcode)
        words.addAll(prefix)
        words.addAll(encoded)
        words.addAll(suffix)
    }

    companion object {
        fun encode(text: String): IntArray {
            val bytes = text.encodeToByteArray()
            val count = bytes.size / 4 + 1
            val result = IntArray(count)
            for (i in bytes.indices) {
                result[i / 4] = result[i / 4] or ((bytes[i].toInt() and 0xFF) shl (8 * (i % 4)))
            }
            return result
        }
    }
}
