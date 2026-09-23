package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ConditionalExpr(
    val condition: Expr,
    val whenTrue: Expr,
    val whenFalse: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "($condition ? $whenTrue : $whenFalse)"
}
