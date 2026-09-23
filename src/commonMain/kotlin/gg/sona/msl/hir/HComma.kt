package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HComma(
    val left: HExpr,
    val right: HExpr,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = right.type
}
