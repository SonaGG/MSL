package gg.sona.msl.ast

class QualifiedName(val segments: List<String>, val global: Boolean = false) {
    val last: String
        get() = segments.last()

    val isSimple: Boolean
        get() = segments.size == 1 && !global

    fun withoutMetalPrefix(): QualifiedName =
        if (segments.size > 1 && segments[0] == "metal") QualifiedName(segments.drop(1)) else this

    override fun equals(other: Any?): Boolean = other is QualifiedName && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = (if (global) "::" else "") + segments.joinToString("::")
}
