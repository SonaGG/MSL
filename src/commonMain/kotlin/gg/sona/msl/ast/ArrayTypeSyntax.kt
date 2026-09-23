package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ArrayTypeSyntax(
    val element: TypeSyntax,
    val size: Expr?,
    override val location: SourceLocation,
) : TypeSyntax() {
    override fun toString(): String = "$element[${size ?: ""}]"
}
