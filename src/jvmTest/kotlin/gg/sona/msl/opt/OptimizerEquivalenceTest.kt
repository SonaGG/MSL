package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrPrinter
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.spirv.SpirvIntrinsicEmitter
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.fail

class OptimizerEquivalenceTest {
    private val corpus: List<File> = File("src/jvmTest/resources/shaders").listFiles { file -> file.extension == "metal" }!!
        .sortedBy { it.name }

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

    private val backends: List<Pair<String, (Intrinsic, Instruction) -> Boolean>> = listOf(
        "spirv" to { intrinsic, _ -> SpirvIntrinsicEmitter.isNative(intrinsic) },
        "dxil" to DxilNativeIntrinsics::isNative,
    )

    private fun prepare(source: String, isNative: (Intrinsic, Instruction) -> Boolean): IrModule {
        val frontend = Frontend(source)
        val module = frontend.lower(lowering)
        if (frontend.errors.isNotEmpty()) error(frontend.errors.joinToString("\n"))
        val expansion = IntrinsicExpansion(isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        return module
    }

    @Test
    fun optimizedShadersBehaveIdentically() {
        val failures = ArrayList<String>()
        var compared = 0
        for (file in corpus) {
            val source = file.readText()
            for ((backend, isNative) in backends) {
                val reference = prepare(source, isNative)
                val optimized = prepare(source, isNative)
                try {
                    Optimizer(OptimizationLevel.Aggressive, isNative).run(optimized)
                } catch (exception: Throwable) {
                    failures.add("${file.name} [$backend]: optimizer crashed\n" + exception.stackTraceToString().lines().take(12).joinToString("\n"))
                    continue
                }
                for (entry in reference.entryPoints) {
                    val other = optimized.entryPoints.first { it.name == entry.name }
                    for (seed in 0L until 6L) {
                        val expected = try {
                            IrInterpreter(entry, seed).run()
                        } catch (limit: InterpreterLimit) {
                            println("skipped ${file.name}/${entry.name} [$backend]: ${limit.message}")
                            break
                        }
                        val actual = try {
                            IrInterpreter(other, seed).run()
                        } catch (limit: InterpreterLimit) {
                            failures.add("${file.name}/${entry.name} [$backend] seed $seed: optimized shader failed: ${limit.message}")
                            break
                        }
                        compared++
                        val difference = difference(expected, actual)
                        if (difference != null) {
                            failures.add("${file.name}/${entry.name} [$backend] seed $seed: $difference\n" + IrPrinter.print(optimized).lines().take(400).joinToString("\n"))
                            break
                        }
                    }
                }
            }
        }
        println("compared $compared executions")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun difference(expected: Execution, actual: Execution): String? {
        if (expected.discarded != actual.discarded) return "discard ${expected.discarded} vs ${actual.discarded}"
        if (expected.discarded) return null
        for ((name, value) in expected.state) {
            val other = actual.state[name] ?: return "missing state $name"
            val mismatch = compare(value, other, name)
            if (mismatch != null) return mismatch
        }
        return null
    }

    private fun compare(expected: Any, actual: Any, path: String): String? = when {
        expected is List<*> && actual is List<*> -> {
            if (expected.size != actual.size) {
                "$path: size ${expected.size} vs ${actual.size}"
            } else {
                expected.indices.firstNotNullOfOrNull { compare(expected[it]!!, actual[it]!!, "$path[$it]") }
            }
        }

        expected is Double && actual is Double -> {
            val close = (expected.isNaN() && actual.isNaN()) || expected == actual ||
                abs(expected - actual) <= 2e-3 + 2e-3 * max(abs(expected), abs(actual))
            if (close || (expected.isInfinite() && actual.isInfinite())) null else "$path: $expected vs $actual"
        }

        else -> if (expected == actual) null else "$path: $expected vs $actual"
    }
}
