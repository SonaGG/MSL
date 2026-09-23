package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import java.io.File
import kotlin.test.Test

class OptimizationReportTest {
    private val lowering = LoweringOptions(
        threadgroupSizes = mapOf("simulate" to WorkgroupSize(64, 1, 1), "flow" to WorkgroupSize(32, 1, 1), "reduce" to WorkgroupSize(32, 1, 1), "blur" to WorkgroupSize(8, 8, 1)),
        threadgroupMemoryLengths = mapOf(0 to 256),
    )

    private fun prepare(source: String): IrModule {
        val module = Frontend(source).lower(lowering)
        val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        return module
    }

    private fun stats(module: IrModule): IntArray {
        val instructions = module.functions.flatMap { it.instructions().toList() }
        return intArrayOf(
            instructions.count { it.opcode != Opcode.Phi && !it.isTerminator },
            module.functions.sumOf { it.blocks.size },
            instructions.count { it.opcode == Opcode.CondBranch || it.opcode == Opcode.Switch },
            instructions.count { it.opcode == Opcode.Load },
        )
    }

    @Test
    fun report() {
        val files = File("src/jvmTest/resources/shaders").listFiles { file -> file.extension == "metal" }!!.sortedBy { it.name }
        val totals = IntArray(8)
        println("shader                     instr  blocks branches loads   (before -> after)")
        for (file in files) {
            val before = stats(prepare(file.readText()))
            val module = prepare(file.readText())
            Optimizer(OptimizationLevel.Aggressive, DxilNativeIntrinsics::isNative).run(module)
            val after = stats(module)
            for (i in 0 until 4) {
                totals[i] += before[i]
                totals[i + 4] += after[i]
            }
            println("%-24s %5d->%-5d %3d->%-3d %3d->%-3d %3d->%-3d".format(file.name, before[0], after[0], before[1], after[1], before[2], after[2], before[3], after[3]))
        }
        println("%-24s %5d->%-5d %3d->%-3d %3d->%-3d %3d->%-3d".format("total", totals[0], totals[4], totals[1], totals[5], totals[2], totals[6], totals[3], totals[7]))
    }
}
