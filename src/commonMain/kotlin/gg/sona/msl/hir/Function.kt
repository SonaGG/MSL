package gg.sona.msl.hir

import gg.sona.msl.ast.Attribute
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class Function(
    val name: String,
    val returnType: Type,
    parameters: List<LocalVariable>,
    val stage: ShaderStage?,
    val attributes: List<Attribute>,
    val location: SourceLocation,
) {
    var parameters: List<LocalVariable> = parameters
    var body: HBlock? = null
    var mangledName: String = name
    var isBeingAnalyzed = false

    val isEntryPoint: Boolean
        get() = stage != null

    override fun toString(): String = "$returnType $name(${parameters.joinToString { "${it.type} ${it.name}" }})"
}
