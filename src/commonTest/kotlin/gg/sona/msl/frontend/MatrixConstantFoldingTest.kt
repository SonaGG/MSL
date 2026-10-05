package gg.sona.msl.frontend

import gg.sona.msl.hir.CompositeConstant
import gg.sona.msl.hir.ScalarConstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatrixConstantFoldingTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;
        constant float3x3 m = float3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
        constant float3 rowTimes = float3(1.0, 0.0, 2.0) * m;
        constant float3 columnTimes = m * float3(1.0, 0.0, 2.0);
        fragment float4 main0() { return float4(rowTimes + columnTimes, 1.0); }
    """.trimIndent()

    @Test
    fun foldsVectorMatrixProducts() {
        val frontend = Frontend(source)
        val program = frontend.analyze()
        assertTrue(frontend.errors.isEmpty(), frontend.errors.joinToString("\n"))
        fun lanes(name: String): List<Double>? = (program.globals.single { it.name == name }.constantValue as? CompositeConstant)?.elements?.map { (it as ScalarConstant).asDouble }
        assertEquals(listOf(7.0, 16.0, 25.0), lanes("rowTimes"))
        assertEquals(listOf(15.0, 18.0, 21.0), lanes("columnTimes"))
    }
}
