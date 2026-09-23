package gg.sona.msl.util

class IntList(initialCapacity: Int = 16) {
    private var data = IntArray(initialCapacity.coerceAtLeast(4))

    var size: Int = 0
        private set

    val isEmpty: Boolean
        get() = size == 0

    fun add(value: Int) {
        if (size == data.size) grow(size + 1)
        data[size++] = value
    }

    fun addAll(values: IntArray) {
        ensureCapacity(size + values.size)
        values.copyInto(data, size)
        size += values.size
    }

    fun addAll(other: IntList) {
        ensureCapacity(size + other.size)
        other.data.copyInto(data, size, 0, other.size)
        size += other.size
    }

    operator fun get(index: Int): Int {
        if (index !in 0..<size) throw IndexOutOfBoundsException("$index !in 0..<$size")
        return data[index]
    }

    operator fun set(index: Int, value: Int) {
        if (index !in 0..<size) throw IndexOutOfBoundsException("$index !in 0..<$size")
        data[index] = value
    }

    fun last(): Int = get(size - 1)

    fun removeLast(): Int {
        val value = last()
        size--
        return value
    }

    fun clear() {
        size = 0
    }

    fun truncate(newSize: Int) {
        require(newSize in 0..size)
        size = newSize
    }

    fun ensureCapacity(capacity: Int) {
        if (capacity > data.size) grow(capacity)
    }

    fun toIntArray(): IntArray = data.copyOf(size)

    fun copyInto(destination: IntArray, destinationOffset: Int = 0) {
        data.copyInto(destination, destinationOffset, 0, size)
    }

    inline fun forEach(action: (Int) -> Unit) {
        for (i in 0..<size) action(get(i))
    }

    private fun grow(minCapacity: Int) {
        var capacity = data.size * 2
        if (capacity < minCapacity) capacity = minCapacity
        data = data.copyOf(capacity)
    }

    override fun toString(): String = toIntArray().joinToString(prefix = "[", postfix = "]")
}
