package gg.sona.msl.sema

import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.hir.Function

class ConcreteOverload(
    override val declaration: FunctionDecl,
    val function: Function,
    val parameterTypes: List<DeclaredType>,
) : FunctionOverload()
