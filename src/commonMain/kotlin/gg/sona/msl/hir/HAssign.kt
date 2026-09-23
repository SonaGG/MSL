package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HAssign(
    val target: HExpr,
    val value: HExpr,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = target.type
}
