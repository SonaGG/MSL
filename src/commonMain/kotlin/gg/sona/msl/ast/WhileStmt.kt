package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class WhileStmt(
    val condition: Expr,
    val body: Stmt,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Stmt()
