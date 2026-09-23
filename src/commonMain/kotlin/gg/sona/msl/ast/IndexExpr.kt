package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class IndexExpr(
    val base: Expr,
    val index: Expr,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "$base[$index]"
}
