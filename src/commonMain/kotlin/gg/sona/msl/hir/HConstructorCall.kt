package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.StructType
import gg.sona.msl.types.Type

class HConstructorCall(
    val constructor: Function,
    val arguments: List<HExpr>,
    val struct: StructType,
    override val location: SourceLocation,
) : HExpr() {
    override val type: Type
        get() = struct
}
