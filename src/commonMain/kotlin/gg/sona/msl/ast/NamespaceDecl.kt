package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class NamespaceDecl(
    val name: String?,
    val declarations: List<Decl>,
    override val location: SourceLocation,
) : Decl()
