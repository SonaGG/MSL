package gg.sona.msl.sema

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.types.Type

class DeclaredType(
    val type: Type,
    val isConst: Boolean,
    val addressSpace: AddressSpace,
    val isReference: Boolean,
) {
    override fun toString(): String = buildString {
        if (addressSpace != AddressSpace.Unspecified) append(addressSpace.spelling).append(' ')
        if (isConst) append("const ")
        append(type)
        if (isReference) append('&')
    }
}
