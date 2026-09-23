package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class FloatLiteralExpr(
    val value: Double,
    val suffix: FloatSuffix,
    val spelling: String,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = spelling
}
