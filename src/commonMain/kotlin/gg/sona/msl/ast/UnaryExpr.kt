package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class UnaryExpr(
    val operator: UnaryOperator,
    val operand: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = if (operator == UnaryOperator.PostIncrement || operator == UnaryOperator.PostDecrement) "($operand${operator.spelling})" else "(${operator.spelling}$operand)"
}
