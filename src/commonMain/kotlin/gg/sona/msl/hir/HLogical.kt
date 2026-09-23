package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HLogical(
    val isAnd: Boolean,
    val left: HExpr,
    val right: HExpr,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr()
