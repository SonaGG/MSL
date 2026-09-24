package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
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
                val prefixes = users.map { prefix(load.type, it.literals) }
                if (prefixes.all { it.isEmpty() }) continue
                for ((user, prefix) in users.zip(prefixes)) {
                    val narrowed = loads.getOrPut(prefix) {
                        if (prefix.isEmpty()) load else builder.load(builder.accessChain(load.operands[0], prefix.map { ConstantScalar.i32(it) }))
                    }
                    val rest = user.literals.copyOfRange(prefix.size, user.literals.size)
                    if (narrowed === load) continue
                    replacements[user] = if (rest.isEmpty()) narrowed else {
                        builder.positionBefore(user)
                        builder.extract(narrowed, *rest)
                    }
                    builder.positionBefore(load)
                }
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun prefix(type: IrType, path: IntArray): List<Int> {
        val prefix = ArrayList<Int>()
        var current = type
        for (index in path) {
            if (current is IrVector) break
            prefix.add(index)
            current = when (current) {
                is IrStruct -> current.members[index].type
                is IrArray -> current.element
                is IrMatrix -> current.column
                else -> return prefix
            }
        }
        return prefix
    }

    private fun narrowable(load: Instruction): Boolean {
        val pointer = load.operands[0].type as? IrPointer ?: return false
        val pointee = pointer.pointee
        if (pointee !is IrMatrix && pointee !is IrStruct && pointee !is IrArray) return false
        return when (Purity.rootStorage(load.operands[0])) {
            StorageClass.Uniform, StorageClass.PushConstant, StorageClass.StorageBuffer -> true
            else -> false
        }
    }
}
