package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class TemplateParameter(
    val name: String,
    val valueType: TypeSyntax?,
    val defaultType: TypeSyntax?,
    val defaultValue: Expr?,
    val location: SourceLocation,
) {
    val isType: Boolean
        get() = valueType == null
}
