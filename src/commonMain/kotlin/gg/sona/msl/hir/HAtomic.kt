package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.Type

class HAtomic(
    val operation: AtomicOperation,
    val pointer: HExpr,
    val value: HExpr?,
    val comparand: HExpr?,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr()
