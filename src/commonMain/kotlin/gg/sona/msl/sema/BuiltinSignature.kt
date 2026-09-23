package gg.sona.msl.sema

import gg.sona.msl.hir.HExpr

class BuiltinSignature(
    val parameters: List<BuiltinParameter>,
    val result: BuiltinParameter,
    val elements: ElementClass,
    val shape: ShapeClass,
    val build: (BuiltinCall) -> HExpr,
)
