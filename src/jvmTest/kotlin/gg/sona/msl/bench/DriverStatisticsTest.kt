package gg.sona.msl.bench

import gg.sona.msl.api.MslCompiler
import gg.sona.msl.api.SpirvShader
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.opt.OptimizationLevel
import java.io.File
import kotlin.test.Test

class DriverStatisticsTest {
    private val lowering = LoweringOptions(
        threadgroupSizes = mapOf(
            "simulate" to WorkgroupSize(64, 1, 1),
            "flow" to WorkgroupSize(32, 1, 1),
            "reduce" to WorkgroupSize(32, 1, 1),
            "blur" to WorkgroupSize(8, 8, 1),
            "resolveBranches" to WorkgroupSize(64, 1, 1),
        ),
        threadgroupMemoryLengths = mapOf(0 to 256),
    )

    private val passthrough = """
        #include <metal_stdlib>
        using namespace metal;
        struct PassthroughOut { float4 position [[position]]; };
        vertex PassthroughOut passthroughVertex(uint id [[vertex_id]]) { PassthroughOut o; o.position = float4(float(id), 0.0, 0.0, 1.0); return o; }
    """.trimIndent()

    private fun compile(source: String, name: String, level: OptimizationLevel): List<SpirvShader>? {
        val result = MslCompiler().compileSpirv(source, name, lowering = lowering, optimization = level)
        return result.shaders.takeIf { result.succeeded }
    }

    private fun pipelines(shaders: List<SpirvShader>, fallbackVertex: SpirvShader): List<Pair<String, List<SpirvShader>>> {
        val vertices = shaders.filter { it.reflection.stage == ShaderStage.Vertex }
        val fragments = shaders.filter { it.reflection.stage == ShaderStage.Fragment }
        val result = ArrayList<Pair<String, List<SpirvShader>>>()
        shaders.filter { it.reflection.stage == ShaderStage.Kernel }.forEach { result.add(it.reflection.entryPoint to listOf(it)) }
        for (fragment in fragments) result.add(fragment.reflection.entryPoint to listOf(vertices.firstOrNull() ?: fallbackVertex, fragment))
        if (fragments.isEmpty()) vertices.forEach { result.add(it.reflection.entryPoint to listOf(it)) }
        return result
    }

    private fun summarize(statistics: Map<String, Map<String, Long>>): Map<String, Long> {
        val totals = LinkedHashMap<String, Long>()
        for (values in statistics.values) {
            for ((name, value) in values) {
                if (name.contains(' ') && !name.startsWith("ISA")) continue
                val key = KEYS.firstOrNull { name.contains(it, ignoreCase = true) } ?: continue
                totals[key] = (totals[key] ?: 0L) + value
            }
        }
        return totals
    }

    @Test
    fun report() {
        val harness = VulkanHarness.create() ?: return println("skip: no Vulkan device with VK_KHR_pipeline_executable_properties")
        harness.use {
            println("device: ${harness.deviceName}")
            val fallback = compile(passthrough, "passthrough.metal", OptimizationLevel.Aggressive)!!.single()
            val files = File("src/jvmTest/resources/shaders").listFiles { file -> file.extension == "metal" }!!.sortedBy { it.name }
            val totals = HashMap<String, Pair<Long, Long>>()
            for (file in files) {
                val source = file.readText()
                val baseline = compile(source, file.name, OptimizationLevel.None) ?: continue
                val optimized = compile(source, file.name, OptimizationLevel.Aggressive) ?: continue
                val baselinePipelines = pipelines(baseline, fallback)
                val optimizedPipelines = pipelines(optimized, fallback).toMap()
                for ((name, stages) in baselinePipelines) {
                    val other = optimizedPipelines[name] ?: continue
                    File("build/isa").mkdirs()
                    val before = runCatching { harness.statistics(stages) }
                    harness.lastRepresentation.forEach { (executable, text) ->
                        File("build/isa/${file.nameWithoutExtension}.$name.$executable.before.txt").writeText(text)
                    }
                    val after = runCatching { harness.statistics(other) }
                    if (before.isFailure || after.isFailure) {
                        println("%-38s failed: %s".format("${file.name}/$name", (before.exceptionOrNull() ?: after.exceptionOrNull())?.message))
                        continue
                    }
                    harness.lastRepresentation.forEach { (executable, text) ->
                        File("build/isa/${file.nameWithoutExtension}.$name.$executable.after.txt").writeText(text)
                    }
                    val a = summarize(before.getOrThrow())
                    val b = summarize(after.getOrThrow())
                    val columns = KEYS.filter { it in a || it in b }.joinToString("  ") { key ->
                        val pair = (a[key] ?: 0L) to (b[key] ?: 0L)
                        totals[key] = (totals[key]?.first ?: 0L) + pair.first to (totals[key]?.second ?: 0L) + pair.second
                        "%s %d->%d".format(LABELS.getValue(key), pair.first, pair.second)
                    }
                    println("%-38s %s".format("${file.name}/$name", columns))
                }
            }
            println("%-38s %s".format("total", KEYS.filter { it in totals }.joinToString("  ") { "%s %d->%d".format(LABELS.getValue(it), totals.getValue(it).first, totals.getValue(it).second) }))
        }
    }

    private companion object {
        val KEYS = listOf("instructions", "dynamic", "alu", "memory", "waits", "Vgprs", "Sgprs", "scratch")
        val LABELS = mapOf("instructions" to "instr", "dynamic" to "dyn", "alu" to "alu", "memory" to "mem", "waits" to "wait", "Vgprs" to "vgpr", "Sgprs" to "sgpr", "scratch" to "scratch")
    }
}
