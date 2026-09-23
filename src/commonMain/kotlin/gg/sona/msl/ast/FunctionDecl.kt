package gg.sona.msl.ast

import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.source.SourceLocation

class FunctionDecl(
    val specifiers: DeclSpecifiers,
    val stage: ShaderStage?,
    val returnType: TypeSyntax,
    val name: String,
    val parameters: List<ParamDecl>,
    val body: BlockStmt?,
    val attributes: List<Attribute>,
    val templateParameters: List<TemplateParameter>?,
    val isConstMethod: Boolean,
    override val location: SourceLocation,
) : Decl()
