package gg.sona.msl.ir

class ConstantComposite(override val type: IrType, val elements: List<IrConstant>) : IrConstant() {
    override fun equals(other: Any?): Boolean = other is ConstantComposite && other.type == type && other.elements == elements

    override fun hashCode(): Int = type.hashCode() * 31 + elements.hashCode()

    override fun toString(): String = "$type{${elements.joinToString()}}"
}
