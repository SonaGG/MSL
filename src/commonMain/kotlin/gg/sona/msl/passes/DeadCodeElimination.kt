package gg.sona.msl.passes

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass

object DeadCodeElimination {
    fun run(function: IrFunction) {
        val live = HashSet<Instruction>()
        val worklist = ArrayDeque<Instruction>()
        for (instruction in function.instructions()) {
            if (hasSideEffects(instruction)) {
                live.add(instruction)
                worklist.add(instruction)
            }
        }
        while (worklist.isNotEmpty()) {
            val instruction = worklist.removeFirst()
            for (operand in instruction.operands) {
                if (operand is Instruction && live.add(operand)) worklist.add(operand)
            }
        }
        for (block in function.blocks) block.instructions.retainAll { it in live }
    }

    private fun hasSideEffects(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Store, Opcode.Call -> true
        Opcode.Intrinsic -> !instruction.intrinsic!!.isPure || instruction.intrinsic!!.name.startsWith("Atomic")
        else -> instruction.isTerminator
    }

    fun removeUnusedGlobals(module: IrModule) {
        val referenced = HashSet<GlobalVariable>()
        for (function in module.functions) {
            for (instruction in function.instructions()) {
                for (operand in instruction.operands) if (operand is GlobalVariable) referenced.add(operand)
            }
        }
        val interfaces = module.entryPoints.flatMap { it.interfaceVariables }.toSet()
        module.globals.retainAll { global ->
            global in referenced || (global in interfaces && global.storage != StorageClass.Private)
        }
        for (entry in module.entryPoints) {
            entry.interfaceVariables.retainAll { it in module.globals }
            for (global in referenced) {
                if (global !in entry.interfaceVariables && usedBy(entry.function, global)) entry.interfaceVariables.add(global)
            }
        }
    }

    private fun usedBy(function: IrFunction, global: GlobalVariable): Boolean =
        function.instructions().any { instruction -> instruction.operands.any { it === global } }
}
