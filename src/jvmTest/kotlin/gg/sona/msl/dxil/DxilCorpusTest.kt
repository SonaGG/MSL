package gg.sona.msl.dxil

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lower.LoweringOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class DxilCorpusTest {
    private val corpus: List<File> = File("src/jvmTest/resources/shaders").listFiles { file -> file.extension == "metal" }!!
        .sortedBy { it.name }

    private val lowering = LoweringOptions(
        threadgroupSizes = mapOf(
            "simulate" to WorkgroupSize(64, 1, 1),
            "flow" to WorkgroupSize(32, 1, 1),
            "reduce" to WorkgroupSize(32, 1, 1),
            "blur" to WorkgroupSize(8, 8, 1),
        ),
        threadgroupMemoryLengths = mapOf(0 to 256),
    )

    private val output = File("build/dxil").also { it.mkdirs() }

    @Test
    fun corpusValidates() {
        val failures = ArrayList<String>()
        for (file in corpus) {
            val result = try {
                MslCompiler().compileDxil(file.readText(), file.name, lowering = lowering)
            } catch (exception: Throwable) {
                failures.add("${file.name}: crashed\n" + exception.stackTraceToString().lines().take(16).joinToString("\n"))
                continue
            }
            if (!result.succeeded) {
                failures.add("${file.name}: compilation failed\n" + result.diagnostics.joinToString("\n"))
                continue
            }
            assertTrue(result.shaders.isNotEmpty(), "${file.name} produced no entry points")
            for (shader in result.shaders) {
                val container = File(output, "${file.nameWithoutExtension}.${shader.reflection.entryPoint}.dxil")
                container.writeBytes(shader.bytes)
                if (!DxilValidator.available) continue
                val error = DxilValidator.validate(container) ?: continue
                failures.add("${file.name}/${shader.reflection.entryPoint}: $error")
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }
}
