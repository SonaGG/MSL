package gg.sona.msl.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemaSmokeTest {
    @Test
    fun analyzesBasicVertexShader() {
        val frontend = Frontend(Samples.HELL)
        val program = frontend.analyze()
        println(program.entryPoints)
        println(program.functions)
        println(program.globals)
        println(program.structs)
        assertTrue(frontend.errors.isEmpty(), frontend.errors.joinToString("\n"))
        assertEquals(listOf("hell"), program.entryPoints.map { it.name })
    }
}
