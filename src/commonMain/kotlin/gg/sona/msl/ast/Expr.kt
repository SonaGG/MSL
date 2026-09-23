package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

sealed class Expr {
    abstract val location: SourceLocation
}
