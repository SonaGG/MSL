package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class AssignExpr(
    val operator: BinaryOperator?,
    val target: Expr,
    val value: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "($target ${operator?.spelling ?: ""}= $value)"
}
