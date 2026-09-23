package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class BlockStmt(
    val statements: List<Stmt>,
    override val location: SourceLocation,
) : Stmt()
