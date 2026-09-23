package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

sealed class TypeSyntax {
    abstract val location: SourceLocation
}
