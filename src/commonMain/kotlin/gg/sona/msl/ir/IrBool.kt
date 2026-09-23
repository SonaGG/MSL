package gg.sona.msl.ir

data object IrBool : IrScalar() {
    override val bits: Int
        get() = 1

    override fun toString(): String = "bool"
}
