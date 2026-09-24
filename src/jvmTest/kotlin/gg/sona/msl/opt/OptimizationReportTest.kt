package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
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
        threadgroupSizes = mapOf("simulate" to WorkgroupSize(64, 1, 1), "flow" to WorkgroupSize(32, 1, 1), "reduce" to WorkgroupSize(32, 1, 1), "blur" to WorkgroupSize(8, 8, 1), "resolveBranches" to WorkgroupSize(64, 1, 1)),
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

    private fun work(instruction: Instruction): Int {
        val lanes = instruction.type.let { if (it is IrMatrix) it.columns * it.rows else it.componentCount.coerceAtLeast(1) }
        return when (instruction.opcode) {
            Opcode.Phi, Opcode.CompositeConstruct, Opcode.CompositeExtract, Opcode.CompositeInsert, Opcode.VectorShuffle,
            Opcode.AccessChain, Opcode.Variable, Opcode.Load, Opcode.Store, Opcode.Bitcast,
            -> 0

            Opcode.MatrixTimesVector, Opcode.VectorTimesMatrix -> {
                val matrix = instruction.operands.first { it.type is IrMatrix }.type as IrMatrix
                matrix.columns * matrix.rows
            }

            Opcode.MatrixTimesMatrix -> lanes * (instruction.operands[0].type as IrMatrix).columns
            Opcode.SDiv, Opcode.UDiv, Opcode.SRem, Opcode.URem -> lanes * INTEGER_DIVISION
            Opcode.FDiv, Opcode.FRem -> lanes * DIVISION
            Opcode.Intrinsic -> when (instruction.intrinsic) {
                Intrinsic.Dot, Intrinsic.Length, Intrinsic.Distance -> instruction.operands[0].type.componentCount
                Intrinsic.Normalize -> 2 * lanes + TRANSCENDENTAL
                in TRANSCENDENTALS -> lanes * TRANSCENDENTAL
                else -> lanes
            }

            else -> if (instruction.isTerminator) 0 else lanes
        }
    }

    private fun scalars(type: IrType): Int = when (type) {
        is IrScalar, is IrVector -> type.componentCount
        is IrMatrix -> type.columns * type.rows
        is IrArray -> type.length.coerceAtLeast(1) * scalars(type.element)
        is IrStruct -> type.members.sumOf { scalars(it.type) }
        else -> 1
    }

    private fun stats(module: IrModule): IntArray {
        val instructions = module.functions.flatMap { it.instructions().toList() }
        return intArrayOf(
            instructions.sumOf { work(it) },
            module.functions.sumOf { it.blocks.size },
            instructions.count { it.opcode == Opcode.CondBranch || it.opcode == Opcode.Switch },
            instructions.filter { it.opcode == Opcode.Load }.sumOf { scalars(it.type) },
        )
    }

    @Test
    fun report() {
        val files = File("src/jvmTest/resources/shaders").listFiles { file -> file.extension == "metal" }!!.sortedBy { it.name }
        val totals = IntArray(8)
        println("shader                   alu cost   blocks branches fetched  (before -> after)")
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

    private companion object {
        const val INTEGER_DIVISION = 20
        const val DIVISION = 2
        const val TRANSCENDENTAL = 4
        val TRANSCENDENTALS = setOf(
            Intrinsic.Sqrt, Intrinsic.Rsqrt, Intrinsic.Sin, Intrinsic.Cos, Intrinsic.Tan, Intrinsic.Exp, Intrinsic.Exp2,
            Intrinsic.Log, Intrinsic.Log2, Intrinsic.Pow, Intrinsic.Powr, Intrinsic.Asin, Intrinsic.Acos, Intrinsic.Atan, Intrinsic.Atan2,
        )
    }
}
