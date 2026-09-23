package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HIf(
    val condition: HExpr,
    val thenBranch: HStmt,
    val elseBranch: HStmt?,
    override val location: SourceLocation,
) : HStmt()
