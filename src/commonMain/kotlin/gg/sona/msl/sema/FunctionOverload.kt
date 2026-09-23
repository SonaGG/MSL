package gg.sona.msl.sema

import gg.sona.msl.ast.FunctionDecl

sealed class FunctionOverload {
    abstract val declaration: FunctionDecl
}
