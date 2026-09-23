package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ContinueStmt(
    override val location: SourceLocation,
) : Stmt()
