package gg.sona.msl.hir

import gg.sona.msl.types.EnumType

class EnumConstant(override val type: EnumType, val value: Long) : ConstValue() {
    override fun equals(other: Any?): Boolean = other is EnumConstant && other.type == type && other.value == value

    override fun hashCode(): Int = type.hashCode() * 31 + value.hashCode()

    override fun toString(): String = type.values.entries.firstOrNull { it.value == value }?.key ?: "$type($value)"
}
