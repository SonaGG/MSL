package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class CastExpr(
    val style: CastStyle,
    val type: TypeSyntax,
    val operand: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "${style.keyword}<$type>($operand)"
}
