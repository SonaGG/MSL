package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class NameExpr(
    val name: QualifiedName,
    val templateArguments: List<TemplateArgument>?,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = name.toString() + (templateArguments?.joinToString(prefix = "<", postfix = ">") ?: "")
}
