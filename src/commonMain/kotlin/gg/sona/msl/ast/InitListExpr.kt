package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class InitListExpr(
    val elements: List<Expr>,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "{${elements.joinToString()}}"
}
