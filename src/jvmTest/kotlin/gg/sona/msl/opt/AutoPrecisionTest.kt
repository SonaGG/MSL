package gg.sona.msl.opt

import gg.sona.msl.api.MslCompiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPrecisionTest {
    private val heavy = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float2 uv;
        };

        fragment float4 heavy(Varyings in [[stage_in]], array<texture2d<float>, 20> maps [[texture(0)]], sampler linear [[sampler(0)]])
        {
            float4 s0 = maps[0].sample(linear, in.uv + float2(0.0 * 0.01, 0.0));
            float4 s1 = maps[1].sample(linear, in.uv + float2(1.0 * 0.01, 0.0));
            float4 s2 = maps[2].sample(linear, in.uv + float2(2.0 * 0.01, 0.0));
            float4 s3 = maps[3].sample(linear, in.uv + float2(3.0 * 0.01, 0.0));
            float4 s4 = maps[4].sample(linear, in.uv + float2(4.0 * 0.01, 0.0));
            float4 s5 = maps[5].sample(linear, in.uv + float2(5.0 * 0.01, 0.0));
            float4 s6 = maps[6].sample(linear, in.uv + float2(6.0 * 0.01, 0.0));
            float4 s7 = maps[7].sample(linear, in.uv + float2(7.0 * 0.01, 0.0));
            float4 s8 = maps[8].sample(linear, in.uv + float2(8.0 * 0.01, 0.0));
            float4 s9 = maps[9].sample(linear, in.uv + float2(9.0 * 0.01, 0.0));
            float4 s10 = maps[10].sample(linear, in.uv + float2(10.0 * 0.01, 0.0));
            float4 s11 = maps[11].sample(linear, in.uv + float2(11.0 * 0.01, 0.0));
            float4 s12 = maps[12].sample(linear, in.uv + float2(12.0 * 0.01, 0.0));
            float4 s13 = maps[13].sample(linear, in.uv + float2(13.0 * 0.01, 0.0));
            float4 s14 = maps[14].sample(linear, in.uv + float2(14.0 * 0.01, 0.0));
            float4 s15 = maps[15].sample(linear, in.uv + float2(15.0 * 0.01, 0.0));
            float4 s16 = maps[16].sample(linear, in.uv + float2(16.0 * 0.01, 0.0));
            float4 s17 = maps[17].sample(linear, in.uv + float2(17.0 * 0.01, 0.0));
            float4 s18 = maps[18].sample(linear, in.uv + float2(18.0 * 0.01, 0.0));
            float4 s19 = maps[19].sample(linear, in.uv + float2(19.0 * 0.01, 0.0));
            float4 color = s0 * s19 + s1 * s18 + s2 * s17 + s3 * s16 + s4 * s15 + s5 * s14 + s6 * s13 + s7 * s12 + s8 * s11 + s9 * s10;
            return color / (color + 1.0);
        }
    """.trimIndent()

    private val light = """
        #include <metal_stdlib>
        using namespace metal;

        fragment float4 light(float4 position [[position]], constant float4& tint [[buffer(0)]])
        {
            float4 color = tint * tint.w;
            return color / (color + 1.0);
        }
    """.trimIndent()

    private fun usesHalf(source: String): Boolean {
        val result = MslCompiler().compileSpirv(source, "auto.metal", precision = FloatPrecision.Auto)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        val words = result.shaders.single().words
        return words.indices.any { i -> words[i] and 0xFFFF == OP_TYPE_FLOAT && words[i] ushr 16 == 3 && words[i + 2] == 16 }
    }

    @Test
    fun relaxesOnlyRegisterBoundShaders() {
        assertTrue(usesHalf(heavy))
        assertFalse(usesHalf(light))
    }

    private companion object {
        const val OP_TYPE_FLOAT = 22
    }
}
