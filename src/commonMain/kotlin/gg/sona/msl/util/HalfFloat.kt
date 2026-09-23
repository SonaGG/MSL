package gg.sona.msl.util

object HalfFloat {
    fun fromFloat(value: Float): Int {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        var mantissa = bits and 0x7FFFFF
        if (exponent == 0xFF) {
            return sign or 0x7C00 or if (mantissa != 0) 0x200 or (mantissa ushr 13) else 0
        }
        val halfExponent = exponent - 127 + 15
        if (halfExponent >= 0x1F) return sign or 0x7C00
        if (halfExponent <= 0) {
            if (halfExponent < -10) return sign
            mantissa = mantissa or 0x800000
            val shift = 14 - halfExponent
            var halfMantissa = mantissa ushr shift
            val remainder = mantissa and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && halfMantissa and 1 != 0)) halfMantissa++
            return sign or halfMantissa
        }
        var result = sign or (halfExponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && result and 1 != 0)) result++
        return result
    }

    fun toFloat(half: Int): Float {
        val sign = (half and 0x8000) shl 16
        val exponent = (half ushr 10) and 0x1F
        val mantissa = half and 0x3FF
        val bits = when {
            exponent == 0 && mantissa == 0 -> sign
            exponent == 0 -> {
                var e = -1
                var m = mantissa
                do {
                    e++
                    m = m shl 1
                } while (m and 0x400 == 0)
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }

            exponent == 0x1F -> sign or 0x7F800000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(bits)
    }

    fun round(value: Double): Double = toFloat(fromFloat(value.toFloat())).toDouble()
}
