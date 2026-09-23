package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HIndex(
    val base: HExpr,
    val index: HExpr,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr() {
    override val isLvalue: Boolean
        get() = base.isLvalue

    override val lvalueAddressSpace: AddressSpace
        get() = base.lvalueAddressSpace

    override val isConstLvalue: Boolean
        get() = base.isConstLvalue
}
