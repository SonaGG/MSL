package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

object LoadNarrowing {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (block in function.blocks) {
            for (load in block.instructions.toList()) {
                if (load.opcode != Opcode.Load || !narrowable(load)) continue
                val users = uses.of(load)
                if (users.isEmpty() || users.any { it.opcode != Opcode.CompositeExtract || it.literals.isEmpty() || it.operands[0] !== load }) continue
                val loads = HashMap<List<Int>, Value>()
                builder.positionBefore(load)
                for (user in users) {
                    val path = user.literals.toList()
                    replacements[user] = loads.getOrPut(path) {
                        builder.load(builder.accessChain(load.operands[0], path.map { ConstantScalar.i32(it) }))
                    }
                }
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun narrowable(load: Instruction): Boolean {
        val pointer = load.operands[0].type as? IrPointer ?: return false
        val pointee = pointer.pointee
        if (pointee !is IrVector && pointee !is IrMatrix && pointee !is IrStruct && pointee !is IrArray) return false
        return when (Purity.rootStorage(load.operands[0])) {
            StorageClass.Uniform, StorageClass.PushConstant, StorageClass.StorageBuffer -> true
            StorageClass.Input -> pointee is IrVector && load.operands[0] is GlobalVariable
            else -> false
        }
    }
}
