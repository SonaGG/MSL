package gg.sona.msl.ir

class IrArray(val element: IrType, val length: Int, val stride: Int) : IrType() {
    val isRuntime: Boolean
        get() = length == 0

    override fun equals(other: Any?): Boolean =
        other is IrArray && other.element == element && other.length == length && other.stride == stride

    override fun hashCode(): Int = (element.hashCode() * 31 + length) * 31 + stride

    override fun toString(): String = "[${if (isRuntime) "?" else length} x $element, stride $stride]"
}
