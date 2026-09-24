package gg.sona.msl.passes

import gg.sona.msl.api.MslCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniformityTest {
    private fun source(index: String) = """
        #include <metal_stdlib>
        using namespace metal;

        struct Bindless {
            array<texture2d<float>, 16384> textures [[id(0)]];
            array<sampler, 512> samplers [[id(16384)]];
        };

        struct Varyings {
            float4 position [[position]];
            float2 uv;
            uint texture [[flat]];
        };

        struct Draw {
            uint texture;
            uint sampler;
        };

        fragment float4 pick(Varyings in [[stage_in]], constant Bindless& bindless [[buffer(0)]], constant Draw& draw [[buffer(1)]])
        {
            uint index = $index;
            uint scaled = index * 2u + 1u;
            return bindless.textures[scaled].sample(bindless.samplers[draw.sampler], in.uv);
        }
    """.trimIndent()

    private fun nonUniformDecorations(words: IntArray): Int {
        var count = 0
        var offset = 5
        while (offset < words.size) {
            val length = words[offset] ushr 16
            val opcode = words[offset] and 0xFFFF
            if (opcode == OP_DECORATE && words[offset + 2] == DECORATION_NON_UNIFORM) count++
            offset += length.coerceAtLeast(1)
        }
        return count
    }

    @Test
    fun uniformIndicesAreNotMarkedNonUniform() {
        val result = MslCompiler().compileSpirv(source("draw.texture"), "uniform.metal")
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        assertEquals(0, nonUniformDecorations(result.shaders.single().words))
    }

    @Test
    fun divergentIndicesAreMarkedNonUniform() {
        val result = MslCompiler().compileSpirv(source("in.texture"), "divergent.metal")
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        assertTrue(nonUniformDecorations(result.shaders.single().words) > 0)
    }

    private companion object {
        const val OP_DECORATE = 71
        const val DECORATION_NON_UNIFORM = 5300
    }
}
