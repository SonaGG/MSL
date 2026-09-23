package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class TypeTemplateArgument(val type: TypeSyntax) : TemplateArgument {
    override val location: SourceLocation
        get() = type.location

    override fun toString(): String = type.toString()
}
