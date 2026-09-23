package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class DoWhileStmt(
    val body: Stmt,
    val condition: Expr,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Stmt()
