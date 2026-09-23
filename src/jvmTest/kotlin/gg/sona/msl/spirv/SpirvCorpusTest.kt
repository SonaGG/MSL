package gg.sona.msl.spirv

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lower.LoweringOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class SpirvCorpusTest {
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

    private fun check(version: SpirvVersion, environment: String) {
        val failures = ArrayList<String>()
        for (file in corpus) {
            val result = try {
                MslCompiler().compileSpirv(file.readText(), file.name, SpirvOptions(version = version), lowering)
            } catch (exception: Throwable) {
                failures.add("${file.name}: crashed\n" + exception.stackTraceToString().lines().take(12).joinToString("\n"))
                continue
            }
            if (!result.succeeded) {
                failures.add("${file.name}: compilation failed\n" + result.diagnostics.joinToString("\n"))
                continue
            }
            assertTrue(result.shaders.isNotEmpty(), "${file.name} produced no entry points")
            if (!SpirvValidator.available) continue
            for (shader in result.shaders) {
                val error = SpirvValidator.validate(shader.toByteArray(), environment) ?: continue
                failures.add("${file.name}/${shader.reflection.entryPoint}: $error\n${SpirvValidator.disassemble(shader.toByteArray())}")
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    @Test
    fun corpusValidatesForVulkan12() = check(SpirvVersion.V1_5, "vulkan1.2")

    @Test
    fun corpusValidatesForVulkan13() = check(SpirvVersion.V1_6, "vulkan1.3")

    @Test
    fun corpusValidatesForVulkan11() = check(SpirvVersion.V1_3, "vulkan1.1")
}
