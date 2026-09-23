package gg.sona.msl.ir

class Parameter(override val type: IrType, val name: String) : Value() {
    override fun toString(): String = "%$name"
}
