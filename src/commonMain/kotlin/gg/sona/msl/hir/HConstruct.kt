package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HConstruct(
    override val type: Type,
    val arguments: List<HExpr>,
    override val location: SourceLocation,
) : HExpr()
