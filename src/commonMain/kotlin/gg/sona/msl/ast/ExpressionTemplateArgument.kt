package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class ExpressionTemplateArgument(val expression: Expr) : TemplateArgument {
    override val location: SourceLocation
        get() = expression.location

    override fun toString(): String = expression.toString()
}
