package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

class HBreak(
    override val location: SourceLocation,
) : HStmt()
