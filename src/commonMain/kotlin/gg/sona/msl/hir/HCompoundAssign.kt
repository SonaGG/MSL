package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HCompoundAssign(
    val operator: HBinaryOperator,
    val target: HExpr,
    val value: HExpr,
    val operationType: Type,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = target.type
}
