package gg.sona.msl.llvm

class LlvmStructType(val name: String?, val elements: List<LlvmType>, val packed: Boolean = false) : LlvmType() {
    override fun equals(other: Any?): Boolean =
        this === other || (name == null && other is LlvmStructType && other.name == null && other.elements == elements && other.packed == packed)

    override fun hashCode(): Int = if (name != null) super.hashCode() else elements.hashCode()

    override fun toString(): String = name?.let { "%$it" } ?: elements.joinToString(prefix = "{ ", postfix = " }")
}
