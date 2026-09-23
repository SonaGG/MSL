package gg.sona.msl.sema

import gg.sona.msl.hir.HExpr

class ArgumentCursor(val arguments: List<HExpr>) {
    var index = 0

    val remaining: Boolean
        get() = index < arguments.size

    fun peek(): HExpr? = arguments.getOrNull(index)

    fun next(): HExpr? = arguments.getOrNull(index++)
}
