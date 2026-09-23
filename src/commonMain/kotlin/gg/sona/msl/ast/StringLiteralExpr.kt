package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class StringLiteralExpr(
    val value: String,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "\"$value\""
}
