package gg.sona.msl.hir

import gg.sona.msl.types.Type

class ZeroConstant(override val type: Type) : ConstValue() {
    override fun equals(other: Any?): Boolean = other is ZeroConstant && other.type == type

    override fun hashCode(): Int = type.hashCode()

    override fun toString(): String = "$type{}"
}
