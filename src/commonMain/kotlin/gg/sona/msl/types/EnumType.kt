package gg.sona.msl.types

class EnumType(
    val name: String,
    val underlying: ScalarType,
    val isScoped: Boolean,
    val isBuiltin: Boolean = false,
) : Type() {
    val values = LinkedHashMap<String, Long>()

    override val scalarKind: ScalarKind
        get() = underlying.kind

    override fun toString(): String = name
}
