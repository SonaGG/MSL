package gg.sona.msl.sema

import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.types.StructType

class TemplateOverload(
    override val declaration: FunctionDecl,
    val scope: Scope,
    val owner: StructType?,
) : FunctionOverload() {
    val instances = HashMap<List<Any>, ConcreteOverload>()
}
