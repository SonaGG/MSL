package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class EmptyStmt(
    override val location: SourceLocation,
) : Stmt()
