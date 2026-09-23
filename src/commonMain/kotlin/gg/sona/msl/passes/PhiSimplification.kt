package gg.sona.msl.passes

import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value

object PhiSimplification {
    fun run(function: IrFunction) {
        while (true) {
            val replacements = HashMap<Value, Value>()
            for (block in function.blocks) {
                for (phi in block.phis) {
                    val distinct = phi.operands.filter { it !== phi && it !is Undef }.distinct()
                    val replacement = when {
                        distinct.size == 1 -> distinct[0]
                        distinct.isEmpty() && phi.operands.isNotEmpty() -> Undef(phi.type)
                        else -> null
                    }
                    if (replacement != null && !(replacement is gg.sona.msl.ir.Instruction && replacement === phi)) {
                        replacements[phi] = replacement
                    }
                }
            }
            if (replacements.isEmpty()) return
            for (block in function.blocks) block.instructions.removeAll { it.opcode == Opcode.Phi && it in replacements }
            for (instruction in function.instructions()) {
                for (i in instruction.operands.indices) {
                    var value = instruction.operands[i]
                    while (true) value = replacements[value] ?: break
                    instruction.operands[i] = value
                }
            }
        }
    }
}
