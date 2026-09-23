package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class SizeofExpr(
    val type: TypeSyntax?,
    val operand: Expr?,
    val isAlignof: Boolean,
    override val location: SourceLocation,
) : Expr() {
    override fun toString(): String = (if (isAlignof) "alignof" else "sizeof") + "(${type ?: operand})"
}
