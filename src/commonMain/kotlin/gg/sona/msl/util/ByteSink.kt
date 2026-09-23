package gg.sona.msl.util

class ByteSink(initialCapacity: Int = 256) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    fun writeByte(value: Int) {
        ensureCapacity(size + 1)
        data[size++] = value.toByte()
    }

    fun writeShortLe(value: Int) {
        ensureCapacity(size + 2)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
    }

    fun writeIntLe(value: Int) {
        ensureCapacity(size + 4)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
        data[size++] = (value ushr 16).toByte()
        data[size++] = (value ushr 24).toByte()
    }

    fun writeLongLe(value: Long) {
        writeIntLe(value.toInt())
        writeIntLe((value ushr 32).toInt())
    }

    fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensureCapacity(size + length)
        bytes.copyInto(data, size, offset, offset + length)
        size += length
    }

    fun writeAscii(text: String, nullTerminated: Boolean = false) {
        ensureCapacity(size + text.length + 1)
        for (c in text) data[size++] = c.code.toByte()
        if (nullTerminated) data[size++] = 0
    }

    fun writeZeros(count: Int) {
        ensureCapacity(size + count)
        data.fill(0, size, size + count)
        size += count
    }

    fun alignTo(alignment: Int) {
        val padding = (alignment - size % alignment) % alignment
        writeZeros(padding)
    }

    fun patchIntLe(offset: Int, value: Int) {
        require(offset + 4 <= size)
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    fun byteAt(offset: Int): Byte = data[offset]

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= data.size) return
        var newCapacity = data.size * 2
        if (newCapacity < capacity) newCapacity = capacity
        data = data.copyOf(newCapacity)
    }
}
