package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ParamDecl(
    val type: TypeSyntax,
    val name: String?,
    val defaultValue: Expr?,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Decl()
