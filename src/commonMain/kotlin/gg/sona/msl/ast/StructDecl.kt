package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class StructDecl(
    val name: String?,
    val members: List<Decl>,
    val attributes: List<Attribute>,
    val templateParameters: List<TemplateParameter>?,
    val isUnion: Boolean,
    val isDefinition: Boolean,
    override val location: SourceLocation,
    val specialization: List<TemplateArgument>? = null,
) : Decl()
