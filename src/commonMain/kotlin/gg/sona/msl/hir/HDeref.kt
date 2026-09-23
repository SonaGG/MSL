package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.Type

class HDeref(
    val pointer: HExpr,
    override val location: SourceLocation,
) : HExpr() {
    private val pointerType: PointerType
        get() = pointer.type as PointerType

    override val type: Type
        get() = pointerType.pointee

    override val isLvalue: Boolean
        get() = true

    override val lvalueAddressSpace: AddressSpace
        get() = pointerType.addressSpace

    override val isConstLvalue: Boolean
        get() = pointerType.isConstPointee
}
