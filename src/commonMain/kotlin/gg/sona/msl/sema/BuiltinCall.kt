package gg.sona.msl.sema

import gg.sona.msl.hir.HExpr
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class BuiltinCall(
    val arguments: List<HExpr>,
    val typeArgument: Type,
    val resultType: Type,
    val location: SourceLocation,
)
