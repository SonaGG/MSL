package gg.sona.msl.frontend

import gg.sona.msl.ir.IrPrinter
import kotlin.test.Test
import kotlin.test.assertTrue

class IrDumpTest {
    @Test
    fun dumpsVertexShader() {
        val frontend = Frontend(Samples.HELL)
        val module = frontend.lower()
        assertTrue(frontend.errors.isEmpty(), frontend.errors.joinToString("\n"))
        println(IrPrinter.print(module))
    }
}
