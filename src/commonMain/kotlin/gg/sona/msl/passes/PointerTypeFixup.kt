package gg.sona.msl.passes

import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.Opcode

object PointerTypeFixup {
    fun run(function: IrFunction) {
        var changed = true
        while (changed) {
            changed = false
            for (block in ControlFlowGraph(function).reversePostorder) {
                for (instruction in block.instructions) {
                    val updated = when (instruction.opcode) {
                        Opcode.AccessChain -> {
                            val base = instruction.operands[0].type as? IrPointer ?: continue
                            var type = base.pointee
                            for (index in instruction.operands.drop(1)) type = IrBuilder.elementType(type, index)
                            IrPointer(type, base.storage)
                        }

                        Opcode.PtrOffset -> instruction.operands[0].type
                        Opcode.Phi, Opcode.Select -> {
                            val source = if (instruction.opcode == Opcode.Phi) instruction.operands.firstOrNull() else instruction.operands[1]
                            if (source?.type is IrPointer) source.type else continue
                        }

                        Opcode.Load -> (instruction.operands[0].type as? IrPointer)?.pointee ?: continue
                        else -> continue
                    }
                    if (updated != instruction.type) {
                        instruction.type = updated
                        changed = true
                    }
                }
            }
        }
    }
}
