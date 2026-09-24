package gg.sona.msl.opt

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.dxil.DxilValidator
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.Opcode
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.spirv.SpirvValidator
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApproximateMathTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float4 values;
        };

        fragment float4 inverse(Varyings in [[stage_in]])
        {
            float4 v = in.values * 4.0 - 2.0;
            float a = atan(v.x) + atan2(v.y, v.z) + tanh(v.w);
            float b = asin(clamp(v.x * 0.4, -1.0, 1.0)) + acos(clamp(v.y * 0.4, -1.0, 1.0));
            float c = sinh(v.z) * 0.1 + cosh(v.w) * 0.1;
            return float4(a, b, c, atan2(v.w, -v.x));
        }
    """.trimIndent()

    private fun optimize(accuracy: MathAccuracy) = Frontend(source).lower(LoweringOptions()).also { module ->
        val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        Optimizer(OptimizationLevel.Aggressive, DxilNativeIntrinsics::isNative, accuracy = accuracy).run(module)
    }

    private fun lanes(value: Any?): List<Double> = when (value) {
        is Double -> listOf(value)
        is List<*> -> value.flatMap(::lanes)
        else -> emptyList()
    }

    @Test
    fun approximationsStayCloseToPreciseResults() {
        val precise = optimize(MathAccuracy.Precise)
        val approximate = optimize(MathAccuracy.Approximate)
        val inverse = setOf(Intrinsic.Atan, Intrinsic.Atan2, Intrinsic.Asin, Intrinsic.Acos, Intrinsic.Tanh, Intrinsic.Sinh, Intrinsic.Cosh)
        assertTrue(approximate.functions.single().instructions().none { it.opcode == Opcode.Intrinsic && it.intrinsic in inverse })
        for (seed in 0L until 16L) {
            val expected = IrInterpreter(precise.entryPoints.single(), seed).run().state
            val actual = IrInterpreter(approximate.entryPoints.single(), seed).run().state
            for ((name, value) in expected) {
                lanes(value).zip(lanes(actual[name])).forEach { (a, b) -> assertTrue(abs(a - b) <= 2e-3 * maxOf(1.0, abs(a)), "$name: $a vs $b (seed $seed)") }
            }
        }
    }

    @Test
    fun approximationsAreValidForBothBackends() {
        val spirv = MslCompiler().compileSpirv(source, "inverse.metal", accuracy = MathAccuracy.Approximate)
        assertTrue(spirv.succeeded, spirv.diagnostics.joinToString("\n"))
        if (SpirvValidator.available) assertNull(SpirvValidator.validate(spirv.shaders.single().toByteArray(), "vulkan1.2"))
        val dxil = MslCompiler().compileDxil(source, "inverse.metal", accuracy = MathAccuracy.Approximate)
        assertTrue(dxil.succeeded, dxil.diagnostics.joinToString("\n"))
        if (!DxilValidator.available) return
        val file = File.createTempFile("approximate", ".dxil")
        try {
            file.writeBytes(dxil.shaders.single().bytes)
            assertNull(DxilValidator.validate(file))
        } finally {
            file.delete()
        }
    }
}
