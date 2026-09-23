package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class EnumDecl(
    val name: String?,
    val isScoped: Boolean,
    val underlyingType: TypeSyntax?,
    val enumerators: List<Enumerator>,
    override val location: SourceLocation,
) : Decl()
