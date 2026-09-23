package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class StaticAssertDecl(
    val condition: Expr,
    val message: String?,
    override val location: SourceLocation,
) : Decl()
