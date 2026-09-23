package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HLoop(
    val condition: HExpr?,
    val body: HStmt,
    val increment: HExpr?,
    val conditionFirst: Boolean,
    override val location: SourceLocation,
) : HStmt()
