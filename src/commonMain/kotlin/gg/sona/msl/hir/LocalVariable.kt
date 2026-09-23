package gg.sona.msl.hir

import gg.sona.msl.ast.Attribute
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class LocalVariable(
    override val name: String,
    override val type: Type,
    override val addressSpace: AddressSpace,
    override val isConst: Boolean,
    val isReference: Boolean,
    val isParameter: Boolean,
    override val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Variable() {
    override fun toString(): String = name
}
