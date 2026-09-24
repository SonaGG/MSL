package gg.sona.msl.opt

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.dxil.DxilValidator
import gg.sona.msl.spirv.SpirvValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageLinkingTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float4 color;
            float2 uv;
            float unused;
            float constantValue;
        };

        vertex Varyings linkedVertex(uint id [[vertex_id]])
        {
            Varyings output;
            output.position = float4(float(id), 0.0, 0.0, 1.0);
            output.color = float4(1.0, 0.5, float(id), sin(float(id)));
            output.uv = float2(float(id), 1.0);
            output.unused = cos(float(id));
            output.constantValue = 2.0;
            return output;
        }

        fragment float4 linkedFragment(Varyings input [[stage_in]])
        {
            return float4(input.color.xy, input.uv.x, input.constantValue);
        }
    """.trimIndent()

    private val links = listOf(StageLink("linkedVertex", "linkedFragment"))

    @Test
    fun removesAndNarrowsVaryingsConsistently() {
        val result = MslCompiler().compileSpirv(source, "linked.metal", links = links)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        val vertex = result.shader("linkedVertex").reflection
        val fragment = result.shader("linkedFragment").reflection
        val outputs = vertex.outputs.filter { it.builtin == null }.associate { it.location to it.type }
        val inputs = fragment.inputs.filter { it.builtin == null }.associate { it.location to it.type }
        assertEquals(outputs, inputs)
        assertEquals(mapOf(0 to "<2 x f32>", 1 to "f32"), inputs)
        if (SpirvValidator.available) result.shaders.forEach { assertNull(SpirvValidator.validate(it.toByteArray(), "vulkan1.2")) }
    }

    @Test
    fun linkedDxilIsValid() {
        val result = MslCompiler().compileDxil(source, "linked.metal", links = links)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        if (!DxilValidator.available) return
        for (shader in result.shaders) {
            val file = File.createTempFile("linked", ".dxil")
            try {
                file.writeBytes(shader.bytes)
                assertNull(DxilValidator.validate(file))
            } finally {
                file.delete()
            }
        }
    }
}
