package gg.sona.msl.ir

class IrStruct(var name: String, val members: List<IrMember>, val size: Int, val alignment: Int) : IrType() {
    override fun toString(): String = "%$name"
}
