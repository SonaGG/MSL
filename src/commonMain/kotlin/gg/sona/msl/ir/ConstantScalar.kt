package gg.sona.msl.ir

class ConstantScalar(override val type: IrScalar, val bits: Long) : IrConstant() {
    val asDouble: Double
        get() = Double.fromBits(bits)

    val asBoolean: Boolean
        get() = bits != 0L

    override fun equals(other: Any?): Boolean = other is ConstantScalar && other.type == type && other.bits == bits

    override fun hashCode(): Int = type.hashCode() * 31 + bits.hashCode()

    override fun toString(): String = when (type) {
        is IrFloat -> "$type ${asDouble}"
        is IrBool -> if (asBoolean) "true" else "false"
        else -> "$type $bits"
    }

    companion object {
        fun bool(value: Boolean) = ConstantScalar(IrBool, if (value) 1 else 0)

        fun int(type: IrInt, value: Long): ConstantScalar {
            val normalized = when {
                type.bits == 64 -> value
                type.signed -> (value shl (64 - type.bits)) shr (64 - type.bits)
                else -> value and ((1L shl type.bits) - 1)
            }
            return ConstantScalar(type, normalized)
        }

        fun float(type: IrFloat, value: Double) = ConstantScalar(type, value.toRawBits())

        fun i32(value: Int) = int(IrInt.I32, value.toLong())

        fun u32(value: Int) = int(IrInt.U32, value.toLong())

        fun f32(value: Float) = float(IrFloat.F32, value.toDouble())
    }
}
