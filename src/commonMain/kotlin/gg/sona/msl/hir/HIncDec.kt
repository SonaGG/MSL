package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HIncDec(
    val target: HExpr,
    val isIncrement: Boolean,
    val isPrefix: Boolean,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = target.type
}
