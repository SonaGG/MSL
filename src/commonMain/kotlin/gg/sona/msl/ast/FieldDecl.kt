package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class FieldDecl(
    val specifiers: DeclSpecifiers,
    val type: TypeSyntax,
    val name: String,
    val defaultValue: Expr?,
    val bitWidth: Expr?,
    val attributes: List<Attribute>,
    override val location: SourceLocation,
) : Decl()
