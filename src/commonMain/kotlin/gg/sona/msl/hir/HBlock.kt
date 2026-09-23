package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HBlock(
    val statements: List<HStmt>,
    override val location: SourceLocation,
) : HStmt()
