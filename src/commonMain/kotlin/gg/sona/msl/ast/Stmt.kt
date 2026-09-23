package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

sealed class Stmt {
    abstract val location: SourceLocation
}
