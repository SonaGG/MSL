package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class CallExpr(
    val callee: Expr,
    val arguments: List<Expr>,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "$callee(${arguments.joinToString()})"
}
