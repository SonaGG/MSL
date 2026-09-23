package gg.sona.msl.types

import gg.sona.msl.ast.Attribute
import gg.sona.msl.source.SourceLocation

class StructField(
    val name: String,
    val type: Type,
    val attributes: List<Attribute>,
    val index: Int,
    val location: SourceLocation,
    val explicitAlignment: Int = 0,
)
