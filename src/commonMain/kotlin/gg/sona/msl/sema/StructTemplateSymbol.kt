package gg.sona.msl.sema

import gg.sona.msl.ast.StructDecl
import gg.sona.msl.types.StructType

class StructTemplateSymbol(val declaration: StructDecl, val scope: Scope) : Symbol() {
    val instances = HashMap<List<Any>, StructType>()
    val specializations = ArrayList<Pair<StructDecl, Scope>>()
}
