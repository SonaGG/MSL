package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class BoolLiteralExpr(
    val value: Boolean,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = value.toString()
}
