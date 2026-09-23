package gg.sona.msl.ast

import gg.sona.msl.source.SourceLocation

class Attribute(
    val namespace: String?,
    val name: String,
    val arguments: List<Expr>,
    val location: SourceLocation,
) {
    override fun toString(): String =
        (namespace?.let { "$it::" } ?: "") + name + if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "(", postfix = ")")
}
