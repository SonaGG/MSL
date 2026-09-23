package gg.sona.msl.ir

class Undef(override val type: IrType) : IrConstant() {
    override fun equals(other: Any?): Boolean = other is Undef && other.type == type

    override fun hashCode(): Int = type.hashCode() + 13

    override fun toString(): String = "$type undef"
}
