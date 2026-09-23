package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class BinaryExpr(
    val operator: BinaryOperator,
    val left: Expr,
    val right: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "($left ${operator.spelling} $right)"
}
