package gg.sona.msl.hir

import gg.sona.msl.ast.Attribute
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

sealed class Variable {
    abstract val name: String
    abstract val type: Type
    abstract val addressSpace: AddressSpace
    abstract val isConst: Boolean
    abstract val attributes: List<Attribute>
    abstract val location: SourceLocation
}
