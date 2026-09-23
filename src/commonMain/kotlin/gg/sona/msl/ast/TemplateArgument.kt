package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

sealed interface TemplateArgument {
    val location: SourceLocation
}
