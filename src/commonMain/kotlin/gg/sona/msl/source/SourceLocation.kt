package gg.sona.msl.source

import kotlin.jvm.JvmInline

@JvmInline
value class SourceLocation(val packed: Long) {
    constructor(fileId: Int, offset: Int, length: Int) : this(
        (fileId.toLong() and FILE_MASK shl FILE_SHIFT) or
            (offset.toLong() and OFFSET_MASK shl OFFSET_SHIFT) or
            (length.toLong().coerceAtMost(LENGTH_MASK) and LENGTH_MASK),
    )

    val fileId: Int
        get() = (packed ushr FILE_SHIFT and FILE_MASK).toInt()

    val offset: Int
        get() = (packed ushr OFFSET_SHIFT and OFFSET_MASK).toInt()

    val length: Int
        get() = (packed and LENGTH_MASK).toInt()

    val end: Int
        get() = offset + length

    val isValid: Boolean
        get() = packed != NONE.packed

    fun extendTo(other: SourceLocation): SourceLocation {
        if (!isValid) return other
        if (!other.isValid || other.fileId != fileId) return this
        val start = minOf(offset, other.offset)
        return SourceLocation(fileId, start, maxOf(end, other.end) - start)
    }

    override fun toString(): String = if (isValid) "$fileId:$offset+$length" else "<none>"

    companion object {
        private const val FILE_SHIFT = 48
        private const val OFFSET_SHIFT = 24
        private const val FILE_MASK = 0xFFFFL
        private const val OFFSET_MASK = 0xFFFFFFL
        private const val LENGTH_MASK = 0xFFFFFFL

        val NONE = SourceLocation(-1L)
    }
}
