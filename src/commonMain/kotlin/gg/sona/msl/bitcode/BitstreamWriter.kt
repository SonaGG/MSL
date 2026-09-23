package gg.sona.msl.bitcode

import gg.sona.msl.util.IntList

class BitstreamWriter {
    private val words = IntList(1024)
    private var current = 0L
    private var bitsInCurrent = 0
    private var abbreviationWidth = 2
    private val blockStack = ArrayList<IntArray>()

    val bitPosition: Long
        get() = words.size.toLong() * 32 + bitsInCurrent

    fun emit(value: Long, width: Int) {
        if (width == 0) return
        require(width <= 32)
        current = current or ((value and ((1L shl width) - 1)) shl bitsInCurrent)
        bitsInCurrent += width
        if (bitsInCurrent >= 32) {
            words.add(current.toInt())
            current = current ushr 32
            bitsInCurrent -= 32
        }
    }

    fun emit(value: Int, width: Int) = emit(value.toLong() and 0xFFFFFFFFL, width)

    fun emitVbr(value: Long, width: Int) {
        val threshold = 1L shl (width - 1)
        var remaining = value
        while (remaining.toULong() >= threshold.toULong()) {
            emit((remaining and (threshold - 1)) or threshold, width)
            remaining = remaining ushr (width - 1)
        }
        emit(remaining, width)
    }

    fun alignTo32() {
        if (bitsInCurrent > 0) emit(0L, 32 - bitsInCurrent)
    }

    fun enterBlock(blockId: Int, width: Int) {
        emit(BitcodeConstants.ENTER_SUBBLOCK.toLong(), abbreviationWidth)
        emitVbr(blockId.toLong(), 8)
        emitVbr(width.toLong(), 4)
        alignTo32()
        val sizeWord = words.size
        emit(0L, 32)
        blockStack.add(intArrayOf(sizeWord, abbreviationWidth))
        abbreviationWidth = width
    }

    fun exitBlock() {
        emit(BitcodeConstants.END_BLOCK.toLong(), abbreviationWidth)
        alignTo32()
        val (sizeWord, previousWidth) = blockStack.removeAt(blockStack.size - 1).let { it[0] to it[1] }
        val length = words.size - sizeWord - 1
        words[sizeWord] = length
        abbreviationWidth = previousWidth
    }

    fun record(code: Int, operands: LongArray) {
        emit(BitcodeConstants.UNABBREV_RECORD.toLong(), abbreviationWidth)
        emitVbr(code.toLong(), 6)
        emitVbr(operands.size.toLong(), 6)
        for (operand in operands) emitVbr(operand, 6)
    }

    fun record(code: Int, operands: List<Long>) = record(code, operands.toLongArray())

    fun record(code: Int) = record(code, LongArray(0))

    fun record(code: Int, first: Long, vararg rest: Long) = record(code, longArrayOf(first) + rest)

    fun toByteArray(): ByteArray {
        alignTo32()
        val result = ByteArray(words.size * 4)
        for (i in 0 until words.size) {
            val word = words[i]
            result[i * 4] = word.toByte()
            result[i * 4 + 1] = (word ushr 8).toByte()
            result[i * 4 + 2] = (word ushr 16).toByte()
            result[i * 4 + 3] = (word ushr 24).toByte()
        }
        return result
    }
}
