package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ReferenceTypeSyntax(
    val referent: TypeSyntax,
    val isRvalue: Boolean,
    override val location: SourceLocation,
) : TypeSyntax() {
    override fun toString(): String = "$referent" + if (isRvalue) "&&" else "&"
}
