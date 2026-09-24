package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import kotlin.test.Test
import kotlin.test.assertEquals

class ExplicitLevelSamplingTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float2 uv;
        };

        fragment float4 levels(Varyings in [[stage_in]], texture2d<float> lut [[texture(0)]], sampler linear [[sampler(0)]],
                               constant float2& lookup [[buffer(0)]])
        {
            return lut.sample(linear, lookup) + lut.sample(linear, in.uv);
        }
    """.trimIndent()

    @Test
    fun usesExplicitLevelOnlyForUniformCoordinates() {
        val module = Frontend(source).lower(LoweringOptions())
        val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        Optimizer(OptimizationLevel.Aggressive, DxilNativeIntrinsics::isNative).run(module)
        val samples = module.functions.single().instructions().filter { it.opcode == Opcode.Intrinsic && it.intrinsic == Intrinsic.TextureSample }.toList()
        assertEquals(listOf(true, false), samples.map { TextureOperands.Lod in TextureOperands(it.literals[0]) }.sortedDescending())
    }
}
