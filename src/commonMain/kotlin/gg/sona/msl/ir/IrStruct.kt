package gg.sona.msl.ir

class IrStruct(val name: String, val members: List<IrMember>, val size: Int, val alignment: Int) : IrType() {
    override fun toString(): String = "%$name"
}
