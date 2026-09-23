package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HVariableRef(
    val variable: Variable,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = variable.type

    override val isLvalue: Boolean
        get() = true

    override val lvalueAddressSpace: AddressSpace
        get() = variable.addressSpace

    override val isConstLvalue: Boolean
        get() = variable.isConst
}
