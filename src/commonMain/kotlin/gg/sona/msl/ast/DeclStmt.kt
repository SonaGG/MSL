package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class DeclStmt(
    val declarations: List<Decl>,
    override val location: SourceLocation,
) : Stmt()
