package gg.sona.msl.hir

import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.util.HalfFloat

class ScalarConstant(override val type: ScalarType, val bits: Long) : ConstValue() {
    val kind: ScalarKind
        get() = type.kind

    val asDouble: Double
        get() = when {
            kind.isFloat -> Double.fromBits(bits)
            kind.isSigned || kind.bits < 64 -> bits.toDouble()
            else -> bits.toULong().toDouble()
        }

    val asLong: Long
        get() = if (kind.isFloat) Double.fromBits(bits).toLong() else bits

    val asBoolean: Boolean
        get() = if (kind.isFloat) Double.fromBits(bits) != 0.0 else bits != 0L

    fun convertTo(target: ScalarType): ScalarConstant = when {
        target.kind == kind -> this
        target.kind.isBool -> of(target, asBoolean)
        target.kind.isFloat -> of(target, asDouble)
        kind.isFloat -> of(target, asDouble.toLong())
        else -> of(target, bits)
    }

    override fun equals(other: Any?): Boolean = other is ScalarConstant && other.type == type && other.bits == bits

    override fun hashCode(): Int = type.hashCode() * 31 + bits.hashCode()

    override fun toString(): String = when {
        kind.isFloat -> "${Double.fromBits(bits)}"
        kind.isBool -> (bits != 0L).toString()
        kind.isSigned -> bits.toString()
        else -> bits.toULong().toString()
    }

    companion object {
        fun of(type: ScalarType, value: Double): ScalarConstant {
            if (!type.kind.isFloat) return of(type, value.toLong())
            val rounded = when (type.kind) {
                ScalarKind.Float -> value.toFloat().toDouble()
                ScalarKind.Half, ScalarKind.BFloat -> HalfFloat.round(value)
                else -> value
            }
            return ScalarConstant(type, rounded.toRawBits())
        }

        fun of(type: ScalarType, value: Long): ScalarConstant {
            if (type.kind.isFloat) return of(type, value.toDouble())
            if (type.kind.isBool) return ScalarConstant(type, if (value != 0L) 1 else 0)
            val bits = type.kind.bits
            val normalized = when {
                bits == 64 -> value
                type.kind.isSigned -> (value shl (64 - bits)) shr (64 - bits)
                else -> value and ((1L shl bits) - 1)
            }
            return ScalarConstant(type, normalized)
        }

        fun of(type: ScalarType, value: Boolean): ScalarConstant = of(type, if (value) 1L else 0L)
    }
}
