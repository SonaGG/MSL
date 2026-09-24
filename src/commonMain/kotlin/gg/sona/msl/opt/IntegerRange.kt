package gg.sona.msl.opt

data class IntegerRange(val low: Long, val high: Long) {
    fun union(other: IntegerRange): IntegerRange = IntegerRange(minOf(low, other.low), maxOf(high, other.high))

    companion object {
        const val LIMIT = 0x7FFFFFFFL

        fun of(low: Long, high: Long): IntegerRange? = if (low in 0..high && high <= LIMIT) IntegerRange(low, high) else null
    }
}
