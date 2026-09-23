package gg.sona.msl.ir

class IrMember(val name: String, val type: IrType, val offset: Int) {
    override fun toString(): String = "$name: $type @$offset"
}
