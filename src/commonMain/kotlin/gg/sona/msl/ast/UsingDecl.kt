package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class UsingDecl(
    val name: QualifiedName,
    override val location: SourceLocation,
) : Decl()
