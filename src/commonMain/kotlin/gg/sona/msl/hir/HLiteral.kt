package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HLiteral(
    val value: ConstValue,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = value.type
}
