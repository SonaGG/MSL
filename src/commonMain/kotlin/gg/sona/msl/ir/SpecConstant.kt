package gg.sona.msl.ir

class SpecConstant(
    override val type: IrScalar,
    val specId: Int,
    val default: ConstantScalar,
    val name: String,
) : Value() {
    override fun toString(): String = "spec($specId) $name"
}
