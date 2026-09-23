package gg.sona.msl.opt

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.passes.UnreachableBlockElimination
import gg.sona.msl.source.Diagnostics

class Optimizer(
    private val level: OptimizationLevel,
    private val isNative: (Intrinsic, Instruction) -> Boolean,
    private val diagnostics: Diagnostics? = null,
) {
    fun run(module: IrModule) {
        if (level == OptimizationLevel.None) return
        markConstantGlobals(module)
        val simplifier = Simplifier(isNative)
        for (function in module.functions) {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                var changed = false
                changed = ConditionalConstantPropagation.run(function) or changed
                changed = cleanup(function) or changed
                changed = simplifier.run(function) or changed
                changed = ValueNumbering.run(function) or changed
                changed = cleanup(function) or changed
                if (!changed) break
            }
        }
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
    }
}
