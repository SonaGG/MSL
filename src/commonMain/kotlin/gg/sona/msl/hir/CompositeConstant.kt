package gg.sona.msl.hir

import gg.sona.msl.types.Type

class CompositeConstant(override val type: Type, val elements: List<ConstValue>) : ConstValue() {
    override fun equals(other: Any?): Boolean = other is CompositeConstant && other.type == type && other.elements == elements

    override fun hashCode(): Int = type.hashCode() * 31 + elements.hashCode()

    override fun toString(): String = "$type{${elements.joinToString()}}"
}
