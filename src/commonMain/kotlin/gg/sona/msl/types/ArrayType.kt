package gg.sona.msl.types

class ArrayType(val element: Type, val size: Int) : Type() {
    val isUnsized: Boolean
        get() = size < 0

    override fun equals(other: Any?): Boolean = other is ArrayType && other.size == size && other.element == element

    override fun hashCode(): Int = element.hashCode() * 31 + size

    override fun toString(): String = "array<$element, ${if (isUnsized) "?" else size}>"
}
