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

    private val interpolated = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float4 color;
            float2 uv;
        };

        vertex Varyings tintedVertex(uint id [[vertex_id]])
        {
            Varyings output;
            output.position = float4(float(id), 0.0, 0.5, 1.0 + float(id));
            output.color = float4(sin(float(id)), cos(float(id)), 0.5, 1.0);
            output.uv = float2(float(id) * 0.1, 0.5);
            return output;
        }

        fragment float4 tintedFragment(Varyings input [[stage_in]], texture2d<float> albedo [[texture(0)]], sampler linear [[sampler(0)]])
        {
            return albedo.sample(linear, input.uv) * saturate(input.color * 1.5);
        }
    """.trimIndent()

    private fun noPerspective(words: IntArray): Int {
        var count = 0
        var offset = 5
        while (offset < words.size) {
            val length = words[offset] ushr 16
            if (words[offset] and 0xFFFF == OP_DECORATE && words[offset + 2] == DECORATION_NO_PERSPECTIVE) count++
            offset += length.coerceAtLeast(1)
        }
        return count
    }

    @Test
    fun relaxesInterpolationOfColorOnlyVaryings() {
        val links = listOf(StageLink("tintedVertex", "tintedFragment"))
        val strict = MslCompiler().compileSpirv(interpolated, "tinted.metal", links = links)
        val relaxed = MslCompiler().compileSpirv(interpolated, "tinted.metal", links = links, relaxInterpolation = true)
        assertTrue(relaxed.succeeded, relaxed.diagnostics.joinToString("\n"))
        assertEquals(0, strict.shaders.sumOf { noPerspective(it.words) })
        assertEquals(0, noPerspective(relaxed.shader("tintedVertex").words))
        assertEquals(1, noPerspective(relaxed.shader("tintedFragment").words))
        if (SpirvValidator.available) relaxed.shaders.forEach { assertNull(SpirvValidator.validate(it.toByteArray(), "vulkan1.2")) }
    }

    private val lit = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float3 normal;
            float2 uv;
        };

        vertex Varyings litVertex(uint id [[vertex_id]])
        {
            Varyings output;
            float angle = float(id) * 0.5;
            output.position = float4(cos(angle), sin(angle), 0.5, 1.0);
            output.normal = float3(sin(angle), cos(angle), 0.25);
            output.uv = float2(float(id) * 0.1, 0.5);
            return output;
        }

        fragment float4 litFragment(Varyings input [[stage_in]], texture2d<float> albedo [[texture(0)]], sampler linear [[sampler(0)]])
        {
            float light = dot(input.normal, float3(0.3, 0.8, 0.5)) * 0.5 + 0.5;
            return albedo.sample(linear, input.uv) * light;
        }
    """.trimIndent()

    @Test
    fun hoistsAffineFragmentWorkIntoTheVertexStage() {
        val links = listOf(StageLink("litVertex", "litFragment"))
        val result = MslCompiler().compileSpirv(lit, "lit.metal", links = links, hoistVaryings = true)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        val inputs = result.shader("litFragment").reflection.inputs.filter { it.builtin == null }.map { it.type }.sorted()
        val outputs = result.shader("litVertex").reflection.outputs.filter { it.builtin == null }.map { it.type }.sorted()
        assertEquals(listOf("<2 x f32>", "f32"), inputs)
        assertEquals(inputs, outputs)
        if (SpirvValidator.available) result.shaders.forEach { assertNull(SpirvValidator.validate(it.toByteArray(), "vulkan1.2")) }
        val dxil = MslCompiler().compileDxil(lit, "lit.metal", links = links, hoistVaryings = true)
        assertTrue(dxil.succeeded, dxil.diagnostics.joinToString("\n"))
        if (!DxilValidator.available) return
        for (shader in dxil.shaders) {
            val file = File.createTempFile("hoisted", ".dxil")
            try {
                file.writeBytes(shader.bytes)
                assertNull(DxilValidator.validate(file))
            } finally {
                file.delete()
            }
        }
    }

    private companion object {
        const val OP_DECORATE = 71
        const val DECORATION_NO_PERSPECTIVE = 13
    }
}
