package gg.sona.msl.opt

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.dxil.DxilValidator
import gg.sona.msl.spirv.SpirvValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrecisionDemotionTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        struct Varyings {
            float4 position [[position]];
            float2 uv;
            float3 normal;
        };

        fragment float4 tonemap(Varyings in [[stage_in]], texture2d<float> albedo [[texture(0)]], sampler linear [[sampler(0)]],
                                constant float4& tint [[buffer(0)]])
        {
            float4 color = albedo.sample(linear, in.uv * 4.0);
            float light = saturate(dot(normalize(in.normal), float3(0.3, 0.8, 0.5)));
            float3 lit = color.rgb * tint.rgb * (light * 0.75 + 0.25);
            lit = lit / (lit + 1.0);
            lit = mix(lit, float3(dot(lit, float3(0.2126, 0.7152, 0.0722))), 0.1);
            return float4(lit, color.a);
        }
    """.trimIndent()

    @Test
    fun relaxedSpirvIsValidAndUsesHalfArithmetic() {
        val result = MslCompiler().compileSpirv(source, "tonemap.metal", precision = FloatPrecision.Relaxed)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        val words = result.shaders.single().words
        assertTrue(words.indices.any { i -> words[i] and 0xFFFF == OP_TYPE_FLOAT && words[i] ushr 16 == 3 && words[i + 2] == 16 })
        if (SpirvValidator.available) assertNull(SpirvValidator.validate(result.shaders.single().toByteArray(), "vulkan1.2"))
    }

    @Test
    fun relaxedDxilIsValid() {
        val result = MslCompiler().compileDxil(source, "tonemap.metal", precision = FloatPrecision.Relaxed)
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        if (!DxilValidator.available) return
        val file = File.createTempFile("relaxed", ".dxil")
        try {
            file.writeBytes(result.shaders.single().bytes)
            assertNull(DxilValidator.validate(file))
            assertTrue(DxilValidator.disassemble(file).contains("half"))
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val OP_TYPE_FLOAT = 22
    }
}
