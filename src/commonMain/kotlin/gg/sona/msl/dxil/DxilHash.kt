package gg.sona.msl.dxil

object DxilHash {
    private val SHIFTS = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)
    private val CONSTANTS = IntArray(64) { index ->
        val value = kotlin.math.abs(kotlin.math.sin((index + 1).toDouble())) * 4294967296.0
        value.toLong().toInt()
    }

    fun compute(data: ByteArray, offset: Int, length: Int): ByteArray {
        val leftOver = length and 0x3F
        val twoRows = leftOver >= 56
        val padAmount = if (twoRows) 120 - leftOver else 56 - leftOver
        val blocks = (length + padAmount + 8) ushr 6
        val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
        val padding = ByteArray(64).also { it[0] = 0x80.toByte() }
        var nextEnd = if (twoRows) blocks - 2 else blocks - 1
        val block = ByteArray(64)
        val words = IntArray(16)
        for (i in 0 until blocks) {
            val position = i * 64
            if (i == nextEnd) {
                block.fill(0)
                if (!twoRows && i == blocks - 1) {
                    val remainder = length - position
                    writeInt(block, 0, length shl 3)
                    data.copyInto(block, 4, offset + position, offset + position + remainder)
                    padding.copyInto(block, 4 + remainder, 0, padAmount)
                    writeInt(block, 60, 1 or (length shl 1))
                } else if (twoRows && i == blocks - 2) {
                    val remainder = length - position
                    data.copyInto(block, 0, offset + position, offset + position + remainder)
                    padding.copyInto(block, remainder, 0, padAmount - 56)
                    nextEnd = blocks - 1
                } else {
                    writeInt(block, 0, length shl 3)
                    padding.copyInto(block, 4, padAmount - 56, padAmount)
                    writeInt(block, 60, 1 or (length shl 1))
                }
                for (w in 0 until 16) words[w] = readInt(block, w * 4)
            } else {
                for (w in 0 until 16) words[w] = readInt(data, offset + position + w * 4)
            }
            round(state, words)
        }
        val result = ByteArray(16)
        for (w in 0 until 4) writeInt(result, w * 4, state[w])
        return result
    }

    private fun round(state: IntArray, x: IntArray) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }

                1 -> {
                    f = (b and d) or (c and d.inv())
                    g = (5 * i + 1) % 16
                }

                2 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) % 16
                }

                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) % 16
                }
            }
            val shift = SHIFTS[(i / 16) * 4 + i % 4]
            val sum = a + f + x[g] + CONSTANTS[i]
            val rotated = (sum shl shift) or (sum ushr (32 - shift))
            a = d
            d = c
            c = b
            b += rotated
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }
}
