package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ExprStmt(
    val expression: Expr,
    override val location: SourceLocation,
) : Stmt()
