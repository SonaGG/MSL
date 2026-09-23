package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HDeclare(
    val variable: LocalVariable,
    val initializer: HExpr?,
    override val location: SourceLocation,
) : HStmt()
