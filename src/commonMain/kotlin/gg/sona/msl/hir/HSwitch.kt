package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HSwitch(
    val selector: HExpr,
    val cases: List<HSwitchCase>,
    override val location: SourceLocation,
) : HStmt()
