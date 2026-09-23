package gg.sona.msl.ir

class IrMember(var name: String, val type: IrType, val offset: Int) {
    override fun toString(): String = "$name: $type @$offset"
}
