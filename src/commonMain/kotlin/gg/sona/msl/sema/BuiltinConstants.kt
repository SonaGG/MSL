package gg.sona.msl.sema

import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.types.ScalarType
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sqrt

object BuiltinConstants {
    private val values: Map<String, ScalarConstant> = buildMap {
        val mathematical = mapOf(
            "M_E" to E,
            "M_LOG2E" to log2(E),
            "M_LOG10E" to log10(E),
            "M_LN2" to ln(2.0),
            "M_LN10" to ln(10.0),
            "M_PI" to PI,
            "M_PI_2" to PI / 2,
            "M_PI_4" to PI / 4,
            "M_1_PI" to 1 / PI,
            "M_2_PI" to 2 / PI,
            "M_2_SQRTPI" to 2 / sqrt(PI),
            "M_SQRT2" to sqrt(2.0),
            "M_SQRT1_2" to sqrt(0.5),
        )
        for ((name, value) in mathematical) {
            put("${name}_F", ScalarConstant.of(ScalarType.Float, value))
            put("${name}_H", ScalarConstant.of(ScalarType.Half, value))
        }
        put("MAXFLOAT", ScalarConstant.of(ScalarType.Float, Float.MAX_VALUE.toDouble()))
        put("HUGE_VALF", ScalarConstant.of(ScalarType.Float, Double.POSITIVE_INFINITY))
        put("HUGE_VALH", ScalarConstant.of(ScalarType.Half, Double.POSITIVE_INFINITY))
        put("INFINITY", ScalarConstant.of(ScalarType.Float, Double.POSITIVE_INFINITY))
        put("NAN", ScalarConstant.of(ScalarType.Float, Double.NaN))
        put("FLT_MAX", ScalarConstant.of(ScalarType.Float, Float.MAX_VALUE.toDouble()))
        put("FLT_MIN", ScalarConstant.of(ScalarType.Float, 1.17549435e-38))
        put("FLT_EPSILON", ScalarConstant.of(ScalarType.Float, 1.1920929e-7))
        put("HALF_MAX", ScalarConstant.of(ScalarType.Half, 65504.0))
        put("HALF_MIN", ScalarConstant.of(ScalarType.Half, 6.103515625e-05))
        put("HALF_EPSILON", ScalarConstant.of(ScalarType.Half, 0.0009765625))
        put("CHAR_BIT", ScalarConstant.of(ScalarType.Int, 8L))
        put("SCHAR_MAX", ScalarConstant.of(ScalarType.Int, 127L))
        put("SCHAR_MIN", ScalarConstant.of(ScalarType.Int, -128L))
        put("CHAR_MAX", ScalarConstant.of(ScalarType.Int, 127L))
        put("CHAR_MIN", ScalarConstant.of(ScalarType.Int, -128L))
        put("UCHAR_MAX", ScalarConstant.of(ScalarType.Int, 255L))
        put("SHRT_MAX", ScalarConstant.of(ScalarType.Int, 32767L))
        put("SHRT_MIN", ScalarConstant.of(ScalarType.Int, -32768L))
        put("USHRT_MAX", ScalarConstant.of(ScalarType.Int, 65535L))
        put("INT_MAX", ScalarConstant.of(ScalarType.Int, Int.MAX_VALUE.toLong()))
        put("INT_MIN", ScalarConstant.of(ScalarType.Int, Int.MIN_VALUE.toLong()))
        put("UINT_MAX", ScalarConstant.of(ScalarType.UInt, 0xFFFFFFFFL))
        put("LONG_MAX", ScalarConstant.of(ScalarType.Long, Long.MAX_VALUE))
        put("LONG_MIN", ScalarConstant.of(ScalarType.Long, Long.MIN_VALUE))
        put("ULONG_MAX", ScalarConstant.of(ScalarType.ULong, -1L))
    }

    fun lookup(name: String): ScalarConstant? = values[name]
}
