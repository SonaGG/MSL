package gg.sona.msl.ir

class IrFloat private constructor(override val bits: Int) : IrScalar() {
    override fun toString(): String = "f$bits"

    companion object {
        val F16 = IrFloat(16)
        val F32 = IrFloat(32)
        val F64 = IrFloat(64)

        fun of(bits: Int): IrFloat = when (bits) {
            16 -> F16
            32 -> F32
            64 -> F64
            else -> error("unsupported float width $bits")
        }
    }
}
