package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class VarDecl(
    val specifiers: DeclSpecifiers,
    val type: TypeSyntax,
    val name: String,
    val initializer: Expr?,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Decl()
