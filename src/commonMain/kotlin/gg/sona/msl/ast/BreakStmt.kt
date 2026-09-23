package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class BreakStmt(
    override val location: SourceLocation,
) : Stmt()
