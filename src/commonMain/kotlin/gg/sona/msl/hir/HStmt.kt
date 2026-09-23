package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation

sealed class HStmt {
    abstract val location: SourceLocation
}
