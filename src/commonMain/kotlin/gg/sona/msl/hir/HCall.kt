package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HCall(
    val function: Function,
    val arguments: List<HExpr>,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = function.returnType
}
