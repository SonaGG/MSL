package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class IfStmt(
    val condition: Expr,
    val thenBranch: Stmt,
    val elseBranch: Stmt?,
    val isConstexpr: Boolean,
    override val location: SourceLocation,
) : Stmt()
