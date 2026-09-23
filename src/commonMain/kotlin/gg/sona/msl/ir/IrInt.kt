package gg.sona.msl.ir

class IrInt private constructor(override val bits: Int, val signed: Boolean) : IrScalar() {
    fun withSignedness(signed: Boolean): IrInt = of(bits, signed)

    override fun toString(): String = (if (signed) "i" else "u") + bits

    companion object {
        private val instances = listOf(8, 16, 32, 64).flatMap { bits -> listOf(IrInt(bits, true), IrInt(bits, false)) }

        fun of(bits: Int, signed: Boolean): IrInt = instances.first { it.bits == bits && it.signed == signed }

        val I8 = of(8, true)
        val U8 = of(8, false)
        val I16 = of(16, true)
        val U16 = of(16, false)
        val I32 = of(32, true)
        val U32 = of(32, false)
        val I64 = of(64, true)
        val U64 = of(64, false)
    }
}
