package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class IntLiteralExpr(
    val value: Long,
    val isUnsigned: Boolean,
    val isLong: Boolean,
    val spelling: String,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = spelling
}
