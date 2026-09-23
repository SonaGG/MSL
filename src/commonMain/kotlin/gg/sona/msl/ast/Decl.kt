package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

sealed class Decl {
    abstract val location: SourceLocation
}
