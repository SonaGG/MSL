package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.ControlFlowGraph

object ValueNumbering {
    private val COMMUTATIVE = setOf(
        Opcode.IAdd, Opcode.IMul, Opcode.FAdd, Opcode.FMul, Opcode.And, Opcode.Or, Opcode.Xor,
        Opcode.LogicalAnd, Opcode.LogicalOr, Opcode.LogicalEqual, Opcode.LogicalNotEqual,
        Opcode.IEqual, Opcode.INotEqual, Opcode.FEqual, Opcode.FNotEqual,
    )

    fun run(function: IrFunction): Boolean {
        val cfg = ControlFlowGraph(function)
        val replacements = HashMap<Value, Value>()
        val scopes = ArrayList<HashMap<List<Any>, Instruction>>()

        fun lookup(key: List<Any>): Instruction? {
            for (i in scopes.indices.reversed()) scopes[i][key]?.let { return it }
            return null
        }

        fun key(instruction: Instruction): List<Any>? {
            if (instruction.opcode == Opcode.Phi) {
                return listOf(instruction.opcode.id, instruction.type, instruction.block!!, instruction.operands.map { IrRewriter.resolve(it, replacements) }, instruction.targets.toList())
            }
            if (!Purity.isRepeatable(instruction)) return null
            var operands: List<Any> = instruction.operands.map { IrRewriter.resolve(it, replacements) }
            if (instruction.opcode in COMMUTATIVE) operands = operands.sortedBy { it.hashCode() }
            return listOf(instruction.opcode.id, instruction.type, operands, instruction.literals.toList(), instruction.intrinsic?.ordinal ?: -1)
        }

        fun visit(block: Block) {
            val scope = HashMap<List<Any>, Instruction>()
            scopes.add(scope)
            for (instruction in block.instructions) {
                val key = key(instruction) ?: continue
                val existing = lookup(key)
                if (existing != null) replacements[instruction] = existing else scope[key] = instruction
            }
            for (child in cfg.dominatorChildren(block)) visit(child)
            scopes.removeAt(scopes.size - 1)
        }

        visit(function.entry)
        IrRewriter.replace(function, replacements)
        return replacements.isNotEmpty()
    }
}
