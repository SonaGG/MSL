package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class TypeAliasDecl(
    val name: String,
    val type: TypeSyntax,
    val templateParameters: List<TemplateParameter>?,
    override val location: SourceLocation,
) : Decl()
