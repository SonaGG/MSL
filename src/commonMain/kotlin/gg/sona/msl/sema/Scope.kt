package gg.sona.msl.sema

import gg.sona.msl.hir.Function
import gg.sona.msl.hir.LocalVariable

class Scope(val parent: Scope?, val name: String? = null, val isNamespace: Boolean = false) {
    val symbols = HashMap<String, Symbol>()
    val usingNamespaces = ArrayList<Scope>()
    var function: Function? = null
    var methodOwner: MethodSet? = null
    var thisVariable: LocalVariable? = null

    fun lookupLocal(name: String): Symbol? {
        symbols[name]?.let { return it }
        for (namespace in usingNamespaces) {
            namespace.symbols[name]?.let { return it }
        }
        return null
    }

    fun lookup(name: String): Symbol? {
        var scope: Scope? = this
        while (scope != null) {
            scope.lookupLocal(name)?.let { return it }
            scope = scope.parent
        }
        return null
    }

    fun enclosingFunction(): Function? {
        var scope: Scope? = this
        while (scope != null) {
            scope.function?.let { return it }
            scope = scope.parent
        }
        return null
    }

    fun enclosingThis(): Pair<LocalVariable, MethodSet>? {
        var scope: Scope? = this
        while (scope != null) {
            val variable = scope.thisVariable
            val owner = scope.methodOwner
            if (variable != null && owner != null) return variable to owner
            scope = scope.parent
        }
        return null
    }

    fun nearestNamespace(): Scope {
        var scope: Scope = this
        while (!scope.isNamespace) scope = scope.parent ?: return scope
        return scope
    }
}
