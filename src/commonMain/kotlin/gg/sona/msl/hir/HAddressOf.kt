package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HAddressOf(
    val operand: HExpr,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr()
