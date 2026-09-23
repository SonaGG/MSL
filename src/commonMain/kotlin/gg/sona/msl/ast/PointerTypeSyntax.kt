package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class PointerTypeSyntax(
    val pointee: TypeSyntax,
    val isConst: Boolean,
    override val location: SourceLocation,
) : TypeSyntax() {
    override fun toString(): String = "$pointee*" + if (isConst) " const" else ""
}
