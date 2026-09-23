package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ForStmt(
    val initializer: Stmt?,
    val condition: Expr?,
    val increment: Expr?,
    val body: Stmt,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Stmt()
