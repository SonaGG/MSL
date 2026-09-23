package gg.sona.msl.opt

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.Mem2Reg
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.passes.UnreachableBlockElimination
import gg.sona.msl.source.Diagnostics

class Optimizer(
    private val level: OptimizationLevel,
    private val isNative: (Intrinsic, Instruction) -> Boolean,
    private val diagnostics: Diagnostics? = null,
) {
    private lateinit var scalarReplacement: ScalarReplacement

    fun run(module: IrModule) {
        if (level == OptimizationLevel.None) return
        markConstantGlobals(module)
        val simplifier = Simplifier(isNative)
        scalarReplacement = ScalarReplacement(if (level == OptimizationLevel.Aggressive) 32 else 8)
        val unrolling = if (level == OptimizationLevel.Aggressive) LoopUnrolling(64, 1024) else LoopUnrolling(8, 128)
        val ifConversion = IfConversion(if (level == OptimizationLevel.Aggressive) AGGRESSIVE_SPECULATION else DEFAULT_SPECULATION)
        for (function in module.functions) {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                var changed = false
                changed = promote(function) or changed
                changed = ConditionalConstantPropagation.run(function) or changed
                changed = cleanup(function) or changed
                changed = simplifier.run(function) or changed
                changed = ValueNumbering.run(function) or changed
                changed = LoopInvariantCodeMotion.run(function) or changed
                changed = unrolling.run(function) or changed
                changed = ifConversion.run(function) or changed
                changed = BlockMerging.run(function) or changed
                changed = cleanup(function) or changed
                if (!changed) break
            }
        }
    }

    private fun promote(function: IrFunction): Boolean {
        val before = function.entry.instructions.count { it.opcode == Opcode.Variable }
        val split = scalarReplacement.run(function)
        Mem2Reg.run(function)
        if (split) return true
        return function.entry.instructions.count { it.opcode == Opcode.Variable } != before
    }

    private fun cleanup(function: IrFunction): Boolean {
        val before = fingerprint(function)
        UnreachableBlockElimination.run(function)
        PhiSimplification.run(function)
        DeadCodeElimination.run(function)
        UnreachableBlockElimination.pruneIncoming(function)
        return fingerprint(function) != before
    }

    private fun fingerprint(function: IrFunction): Long {
        var hash = function.blocks.size.toLong()
        for (block in function.blocks) {
            hash = hash * 31 + block.instructions.size
            for (instruction in block.instructions) hash = hash * 31 + instruction.opcode.id + instruction.operands.size * 7
        }
        return hash
    }

    private fun markConstantGlobals(module: IrModule) {
        val stored = HashSet<GlobalVariable>()
        for (function in module.functions) {
            for (instruction in function.instructions()) {
                val pointer = when {
                    instruction.opcode == Opcode.Store -> instruction.operands[0]
                    instruction.opcode == Opcode.Intrinsic && instruction.intrinsic!!.name.startsWith("Atomic") -> instruction.operands[0]
                    else -> continue
                }
                Purity.rootGlobal(pointer)?.let { stored.add(it) }
            }
        }
        for (global in module.globals) {
            global.isConstant = global.storage == StorageClass.Private && global.initializer != null && global !in stored
        }
    }

    private companion object {
        const val MAX_ROUNDS = 16
        const val AGGRESSIVE_SPECULATION = 24
        const val DEFAULT_SPECULATION = 8
    }
}
