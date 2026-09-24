package gg.sona.msl.opt

import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.frontend.Frontend
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrPrinter
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PhiSimplification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeFoldingTest {
    private val source = """
        #include <metal_stdlib>
        using namespace metal;

        kernel void ranges(device uint* output [[buffer(4)]], device const uint* input [[buffer(5)]],
                           uint lid [[thread_position_in_threadgroup]], uint lane [[thread_index_in_simdgroup]],
                           uint gid [[thread_position_in_grid]])
        {
            uint value = 0;
            if (lid < 64u) {
                value += lid % 64u;
            }
            value += lane & 127u;
            value += uint(int(lid) / 4);
            if (input[gid] < 10u) {
                value += 1u;
            }
            output[gid] = value;
        }
    """.trimIndent()

    private fun optimize(specializable: Boolean): List<Instruction> {
        val lowering = LoweringOptions(threadgroupSizes = mapOf("ranges" to WorkgroupSize(64, 1, 1)), specializableThreadgroupSize = specializable)
        val module = Frontend(source).lower(lowering)
        val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
        for (function in module.functions) {
            expansion.run(function)
            PhiSimplification.run(function)
            DeadCodeElimination.run(function)
        }
        Optimizer(OptimizationLevel.Aggressive, DxilNativeIntrinsics::isNative).run(module)
        return module.functions.single().instructions().toList()
    }

    @Test
    fun foldsChecksAndArithmeticWithKnownRanges() {
        val instructions = optimize(specializable = false)
        assertEquals(0, instructions.count { it.opcode == Opcode.URem || it.opcode == Opcode.SDiv || it.opcode == Opcode.And })
        assertEquals(1, instructions.count { it.opcode == Opcode.ULess }, instructions.joinToString("\n") { IrPrinter.format(it) })
    }

    @Test
    fun keepsChecksWhenWorkgroupSizeIsSpecializable() {
        val instructions = optimize(specializable = true)
        assertTrue(instructions.count { it.opcode == Opcode.ULess || it.opcode == Opcode.UGreaterEqual } >= 2)
    }
}
