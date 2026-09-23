package gg.sona.msl.dxil

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DxilHashTest {
    @Test
    fun matchesValidatorSignedContainer() {
        val container = File("src/jvmTest/resources/reference_ps.dxil").readBytes()
        val expected = container.copyOfRange(4, 20)
        assertContentEquals(expected, DxilHash.compute(container, 20, container.size - 20))
    }
}
