package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.Opcode
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecializationTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        struct Light {
            float3 color;
            float intensity;
        };

        struct Settings {
            uint mode;
            float exposure;
            Light lights[2];
        };

        fragment float4 specialized(float4 position [[position]], constant Settings& settings [[buffer(3)]])
        {
            float3 color = float3(position.xy * 0.001, 0.5);
            if (settings.mode == 0u) {
                color = sqrt(color) * settings.exposure;
            } else if (settings.mode == 1u) {
                color = color * color;
            } else {
                color = settings.lights[1].color * settings.lights[1].intensity;
            }
            return float4(color, 1.0);
        }
    """.trimIndent()

    private fun optimize(specialization: Specialization): List<Instruction> {
        val module = Frontend(source).lower(LoweringOptions())
        val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        Optimizer(OptimizationLevel.Aggressive, DxilNativeIntrinsics::isNative, null, specialization).run(module)
        return module.functions.single().instructions().toList()
    }

    @Test
    fun bakesUniformValuesAndRemovesDeadPaths() {
        val generic = optimize(Specialization())
        assertTrue(generic.any { it.opcode == Opcode.Load && it.type.toString() == "u32" })

        val specialized = optimize(
            Specialization(
                mapOf(
                    UniformField(3, "mode") to 2,
                    UniformField(3, "lights[1].color") to floatArrayOf(1f, 0.5f, 0.25f),
                    UniformField(3, "lights[1].intensity") to 2f,
                ),
            ),
        )
        assertEquals(0, specialized.count { it.opcode == Opcode.CondBranch || it.opcode == Opcode.Select || it.opcode == Opcode.Load })
        val stored = specialized.single { it.opcode == Opcode.Store }.operands[1]
        assertTrue(stored is IrConstant && stored.toString().contains("0.5"), "expected a constant result, got $stored")
    }
}
