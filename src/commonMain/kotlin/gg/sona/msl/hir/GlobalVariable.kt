package gg.sona.msl.hir

import gg.sona.msl.ast.Attribute
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class GlobalVariable(
    override val name: String,
    override val type: Type,
    override val addressSpace: AddressSpace,
    override val isConst: Boolean,
    override val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Variable() {
    var initializer: HExpr? = null
    var constantValue: ConstValue? = null
    var functionConstantIndex: Int = -1
    var samplerState: SamplerState? = null

    val isFunctionConstant: Boolean
        get() = functionConstantIndex >= 0

    override fun toString(): String = name
}
