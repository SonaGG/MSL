package gg.sona.msl.types

import gg.sona.msl.lang.AddressSpace

class PointerType(
    val pointee: Type,
    val addressSpace: AddressSpace,
    val isConstPointee: Boolean = false,
) : Type() {
    override fun equals(other: Any?): Boolean =
        other is PointerType && other.pointee == pointee && other.addressSpace == addressSpace &&
            other.isConstPointee == isConstPointee

    override fun hashCode(): Int = (pointee.hashCode() * 31 + addressSpace.hashCode()) * 31 + isConstPointee.hashCode()

    override fun toString(): String = "${addressSpace.spelling} ${if (isConstPointee) "const " else ""}$pointee*"
}
