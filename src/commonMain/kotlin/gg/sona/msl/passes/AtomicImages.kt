package gg.sona.msl.passes

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object AtomicImages {
    fun run(module: IrModule) {
        val instructions = module.functions.flatMap { function -> function.blocks.flatMap { it.instructions } }
        val atomic = instructions.filter { it.intrinsic == Intrinsic.TextureAtomic }.mapNotNull { root(it.operands[0]) }.toSet()
        if (atomic.isEmpty()) return
        atomic.forEach { it.valueType = retagged(it.valueType) }
        instructions.filter { (it.opcode == Opcode.Load || it.opcode == Opcode.AccessChain) && root(it) in atomic }.forEach { it.type = retagged(it.type) }
    }

    private fun root(value: Value): GlobalVariable? = when {
        value is GlobalVariable -> value
        value is Instruction && (value.opcode == Opcode.Load || value.opcode == Opcode.AccessChain) -> root(value.operands[0])
        else -> null
    }

    private fun retagged(type: IrType): IrType = when (type) {
        is IrImage -> type.withAtomic()
        is IrArray -> IrArray(retagged(type.element), type.length, type.stride)
        is gg.sona.msl.ir.IrPointer -> gg.sona.msl.ir.IrPointer(retagged(type.pointee), type.storage)
        else -> type
    }
}
