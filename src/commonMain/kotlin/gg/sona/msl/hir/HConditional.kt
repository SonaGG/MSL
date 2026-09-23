package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HConditional(
    val condition: HExpr,
    val whenTrue: HExpr,
    val whenFalse: HExpr,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr() {
    override val isLvalue: Boolean
        get() = whenTrue.isLvalue && whenFalse.isLvalue && whenTrue.type == whenFalse.type &&
            whenTrue.lvalueAddressSpace == whenFalse.lvalueAddressSpace

    override val lvalueAddressSpace: AddressSpace
        get() = whenTrue.lvalueAddressSpace
}
