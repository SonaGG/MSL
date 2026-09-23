package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value

object MemoryForwarding {
    fun run(function: IrFunction): Boolean {
        val replacements = HashMap<Value, Value>()
        val dead = HashSet<Instruction>()
        for (block in function.blocks) {
            forward(block, replacements)
            eliminateStores(block, dead)
        }
        if (replacements.isEmpty() && dead.isEmpty()) return false
        for (block in function.blocks) block.instructions.removeAll { it in dead }
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun key(pointer: Value): Any {
        val chain = pointer as? Instruction ?: return pointer
        if (chain.opcode != Opcode.AccessChain) return pointer
        return listOf(key(chain.operands[0])) + chain.operands.drop(1).map { (it as? ConstantScalar)?.bits ?: it }
    }

    private fun root(pointer: Value): Value {
        var current = pointer
        while (current is Instruction && (current.opcode == Opcode.AccessChain || current.opcode == Opcode.PtrOffset)) current = current.operands[0]
        return current
    }

    private fun storage(pointer: Value): StorageClass? = (pointer.type as? IrPointer)?.storage

    private fun mayAlias(first: Value, second: Value): Boolean {
        val a = root(first)
        val b = root(second)
        if (a === b) return true
        val known = { value: Value -> value is GlobalVariable || value is Instruction && value.opcode == Opcode.Variable }
        if (!known(a) || !known(b)) return true
        return storage(a) == StorageClass.StorageBuffer && storage(b) == StorageClass.StorageBuffer
    }

    private fun clobbers(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Call -> true
        Opcode.Intrinsic -> !Purity.isRepeatable(instruction)
        else -> false
    }

    private fun forward(block: Block, replacements: HashMap<Value, Value>) {
        val available = HashMap<Any, Pair<Value, Value>>()
        for (instruction in block.instructions) {
            for (i in instruction.operands.indices) instruction.operands[i] = IrRewriter.resolve(instruction.operands[i], replacements)
            when {
                instruction.opcode == Opcode.Load -> {
                    val pointer = instruction.operands[0]
                    val key = key(pointer)
                    val known = available[key]
                    if (known != null && known.second.type == instruction.type) {
                        replacements[instruction] = known.second
                    } else {
                        available[key] = pointer to instruction
                    }
                }

                instruction.opcode == Opcode.Store -> {
                    val pointer = instruction.operands[0]
                    available.values.removeAll { mayAlias(it.first, pointer) }
                    available[key(pointer)] = pointer to instruction.operands[1]
                }

                clobbers(instruction) -> available.clear()
            }
        }
    }

    private fun eliminateStores(block: Block, dead: HashSet<Instruction>) {
        val overwritten = HashMap<Any, Value>()
        for (instruction in block.instructions.asReversed()) {
            when {
                instruction.opcode == Opcode.Store -> {
                    val pointer = instruction.operands[0]
                    val key = key(pointer)
                    if (key in overwritten) dead.add(instruction) else overwritten[key] = pointer
                }

                instruction.opcode == Opcode.Load -> {
                    val pointer = instruction.operands[0]
                    overwritten.values.removeAll { mayAlias(it, pointer) }
                }

                instruction.opcode == Opcode.Intrinsic && instruction.intrinsic!!.name.startsWith("Atomic") -> overwritten.clear()
                clobbers(instruction) -> overwritten.clear()
            }
        }
    }
}
