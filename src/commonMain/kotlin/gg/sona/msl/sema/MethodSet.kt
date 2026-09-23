package gg.sona.msl.sema

import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.types.StructType

class MethodSet(val struct: StructType, val scope: Scope) {
    val methods = HashMap<String, MutableList<FunctionDecl>>()
    val instances = HashMap<Pair<FunctionDecl, AddressSpace>, ConcreteOverload>()
}
