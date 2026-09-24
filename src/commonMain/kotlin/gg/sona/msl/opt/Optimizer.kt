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
    specialization: Specialization = Specialization(),
    private val precision: FloatPrecision = FloatPrecision.Full,
) {
    private val uniformSpecialization = UniformSpecialization(specialization)

    private lateinit var scalarReplacement: ScalarReplacement
    private val aggressive = level == OptimizationLevel.Aggressive

    fun run(module: IrModule) {
        if (level == OptimizationLevel.None) return
        markConstantGlobals(module)
        val simplifier = Simplifier(isNative)
        scalarReplacement = ScalarReplacement(if (aggressive) 32 else 8)
        val unrolling = if (aggressive) LoopUnrolling(64, 1024) else LoopUnrolling(8, 128)
        val ifConversion = IfConversion(if (aggressive) AGGRESSIVE_SPECULATION else DEFAULT_SPECULATION, UNIFORM_SPECULATION)
        val scalarization = Scalarization(isNative)
        val fmaFormation = FmaFormation(isNative)
        val strengthReduction = StrengthReduction(isNative)
        for (function in module.functions) {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                var changed = false
                changed = promote(function) or changed
                if (rounds == 1 && precision == FloatPrecision.Relaxed) {
                    val stage = module.entryPoints.firstOrNull { it.function === function }?.stage
                    if (stage != null) changed = PrecisionDemotion(isNative).run(function, stage) or changed
                }
                if (aggressive) {
                    changed = scalarization.run(function) or changed
                    changed = LoadNarrowing.run(function) or changed
                    changed = MemoryForwarding.run(function) or changed
                }
                changed = uniformSpecialization.run(function) or changed
                changed = ConditionalConstantPropagation.run(function) or changed
                changed = cleanup(function) or changed
                changed = simplifier.run(function) or changed
                if (aggressive) {
                    changed = Reassociation.run(function) or changed
                    changed = strengthReduction.run(function) or changed
                }
                changed = ValueNumbering.run(function) or changed
                changed = LoopInvariantCodeMotion.run(function) or changed
                changed = unrolling.run(function) or changed
                changed = ifConversion.run(function) or changed
                changed = BlockMerging.run(function) or changed
                changed = cleanup(function) or changed
                if (!changed) break
            }
            if (aggressive) {
                var sinks = 0
                while (sinks < MAX_ROUNDS && CodeSinking.run(function)) sinks++
                if (fmaFormation.run(function)) cleanup(function)
                Scheduling.run(function)
            }
        }
        diagnostics?.let { report -> module.entryPoints.forEach { InterfaceHints.report(it, report) } }
        finish(module)
    }

    private fun finish(module: IrModule) {
        if (aggressive) NameMangling.run(module)
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
        const val UNIFORM_SPECULATION = 4
    }
}
