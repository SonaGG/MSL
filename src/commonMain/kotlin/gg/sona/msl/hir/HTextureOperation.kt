package gg.sona.msl.hir

import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.SampleOptionKind
import gg.sona.msl.types.Type

class HTextureOperation(
    val operation: TextureOperation,
    val texture: HExpr,
    val sampler: HExpr?,
    val coordinate: HExpr?,
    val arrayIndex: HExpr?,
    val sampleOrLod: HExpr?,
    val option: HExpr?,
    val optionKind: SampleOptionKind?,
    val minLodClamp: HExpr?,
    val offset: HExpr?,
    val compareValue: HExpr?,
    val value: HExpr?,
    val component: Int,
    override val type: Type,
    override val location: SourceLocation,
) : HExpr()
