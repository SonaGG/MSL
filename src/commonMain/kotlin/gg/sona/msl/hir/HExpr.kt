package gg.sona.msl.hir

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

sealed class HExpr {
    abstract val type: Type
    abstract val location: SourceLocation

    open val isLvalue: Boolean
        get() = false

    open val lvalueAddressSpace: AddressSpace
        get() = AddressSpace.Thread

    open val isConstLvalue: Boolean
        get() = false
}
