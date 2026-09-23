package gg.sona.msl.opt

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.source.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceHintTest {
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

        vertex Varyings hintVertex(uint id [[vertex_id]])
        {
            Varyings output;
            output.position = float4(float(id), 0.0, 0.0, 1.0);
            output.color = float4(1.0, 0.5, float(id), 1.0);
            output.uv = float2(float(id), 1.0);
            output.unused = 3.0;
            output.constantValue = 2.0;
            return output;
        }

        fragment float4 hintFragment(Varyings input [[stage_in]], texture2d<float> idle [[texture(0)]])
        {
            return float4(input.color.xy, input.uv.x, 1.0);
        }
    """.trimIndent()

    @Test
    fun reportsInterfaceOpportunities() {
        val result = MslCompiler().compileSpirv(source, "hints.metal")
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        val hints = result.diagnostics.filter { it.severity == Severity.PerformanceHint }.map { it.message }
        fun expect(fragment: String) = assertTrue(hints.any { fragment in it }, "missing hint '$fragment' in:\n" + hints.joinToString("\n"))
        expect("input 'unused' is never read")
        expect("only components .xy of input 'color'")
        expect("resource 'idle' is bound but never used")
        expect("output 'constantValue' is always")
        assertEquals(0, result.diagnostics.count { it.severity == Severity.Error })
    }
}
