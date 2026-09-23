package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class MemberInitializer(
    val name: String,
    val arguments: List<Expr>,
    val braced: Boolean,
    val location: SourceLocation,
)
