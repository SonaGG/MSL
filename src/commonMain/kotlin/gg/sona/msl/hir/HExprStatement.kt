package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HExprStatement(
    val expression: HExpr,
    override val location: SourceLocation,
) : HStmt()
