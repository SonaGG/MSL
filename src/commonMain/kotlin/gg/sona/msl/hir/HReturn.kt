package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HReturn(
    val value: HExpr?,
    override val location: SourceLocation,
) : HStmt()
