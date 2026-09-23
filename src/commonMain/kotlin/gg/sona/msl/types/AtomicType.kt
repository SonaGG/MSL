package gg.sona.msl.types

class AtomicType(val element: ScalarType) : Type() {
    override val scalarKind: ScalarKind
        get() = element.kind

    override fun equals(other: Any?): Boolean = other is AtomicType && other.element == element

    override fun hashCode(): Int = element.hashCode() + 17

    override fun toString(): String = "atomic<$element>"
}
