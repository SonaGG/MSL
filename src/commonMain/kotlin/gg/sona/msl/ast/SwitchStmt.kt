package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class SwitchStmt(
    val condition: Expr,
    val body: Stmt,
    override val location: SourceLocation,
) : Stmt()
