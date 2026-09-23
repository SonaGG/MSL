package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class MemberExpr(
    val base: Expr,
    val member: String,
    val isArrow: Boolean,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = "$base${if (isArrow) "->" else "."}$member"
}
