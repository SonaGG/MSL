package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class CaseStmt(
    val value: Expr?,
    val body: Stmt,
    override val location: SourceLocation,
) : Stmt()
