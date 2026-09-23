package gg.sona.msl.ir

class ConstantNull(override val type: IrType) : IrConstant() {
    override fun equals(other: Any?): Boolean = other is ConstantNull && other.type == type

    override fun hashCode(): Int = type.hashCode() + 7

    override fun toString(): String = "$type zeroinitializer"
}
