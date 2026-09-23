package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HPointerOffset(
    val pointer: HExpr,
    val offset: HExpr,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = pointer.type
}
