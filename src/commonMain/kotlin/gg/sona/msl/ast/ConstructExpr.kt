package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ConstructExpr(
    val type: TypeSyntax,
    val arguments: List<Expr>,
    val isBraced: Boolean,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "$type" + if (isBraced) "{${arguments.joinToString()}}" else "(${arguments.joinToString()})"
}
