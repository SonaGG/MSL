package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.StructField
import gg.sona.msl.types.Type

class HMember(
    val base: HExpr,
    val member: StructField,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = member.type

    override val isLvalue: Boolean
        get() = base.isLvalue

    override val lvalueAddressSpace: AddressSpace
        get() = base.lvalueAddressSpace

    override val isConstLvalue: Boolean
        get() = base.isConstLvalue
}
