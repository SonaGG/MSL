package gg.sona.msl.ir

sealed class IrScalar : IrType() {
    abstract val bits: Int

    override val isScalar: Boolean
        get() = true
}
