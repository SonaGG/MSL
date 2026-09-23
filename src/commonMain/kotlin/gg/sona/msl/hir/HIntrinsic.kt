package gg.sona.msl.hir

import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HIntrinsic(
    val intrinsic: Intrinsic,
    val arguments: List<HExpr>,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr()
