package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

object ConditionalConstantPropagation {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val lattice = HashMap<Instruction, LatticeValue>()
        val executable = HashSet<Block>()
        val edges = HashSet<Pair<Block, Block>>()
        val blockWork = ArrayDeque<Block>()
        val valueWork = ArrayDeque<Instruction>()

        fun valueOf(value: Value): LatticeValue = when (value) {
            is Instruction -> lattice[value] ?: LatticeValue.Unknown
            is Undef -> LatticeValue.Overdefined
            is IrConstant -> if (ConstantFolding.isFoldable(value)) LatticeValue.of(value) else LatticeValue.Overdefined
            else -> LatticeValue.Overdefined
        }

        fun update(instruction: Instruction, value: LatticeValue) {
            val old = lattice[instruction] ?: LatticeValue.Unknown
            val merged = old.meet(value)
            if (merged.isOverdefined == old.isOverdefined && merged.constant == old.constant) return
            lattice[instruction] = merged
            valueWork.addAll(uses.of(instruction))
        }

        fun markEdge(from: Block, to: Block) {
            if (!edges.add(from to to)) return
            if (executable.add(to)) {
                blockWork.add(to)
            } else {
                to.phis.forEach { valueWork.add(it) }
            }
        }

        fun branchOn(block: Block, instruction: Instruction, condition: LatticeValue, target: (ConstantScalar) -> Block) {
            val constant = condition.constant as? ConstantScalar
            when {
                constant != null -> markEdge(block, target(constant))
                condition.isOverdefined || condition.constant != null -> instruction.targets.forEach { markEdge(block, it) }
            }
        }

        fun visit(instruction: Instruction) {
            val block = instruction.block ?: return
            if (block !in executable) return
            when (instruction.opcode) {
                Opcode.Phi -> {
                    var result = LatticeValue.Unknown
                    for (i in instruction.operands.indices) {
                        if ((instruction.targets[i] to block) in edges) result = result.meet(valueOf(instruction.operands[i]))
                    }
                    update(instruction, result)
                }

                Opcode.Branch -> markEdge(block, instruction.targets[0])
                Opcode.CondBranch -> branchOn(block, instruction, valueOf(instruction.operands[0])) {
                    instruction.targets[if (it.asBoolean) 0 else 1]
                }

                Opcode.Switch -> branchOn(block, instruction, valueOf(instruction.operands[0])) { switchTarget(instruction, it) }
                Opcode.Return, Opcode.Unreachable -> Unit
                else -> {
                    if (instruction.type == IrVoid || !Purity.isFoldable(instruction)) {
                        update(instruction, LatticeValue.Overdefined)
                        return
                    }
                    val operands = instruction.operands.map { valueOf(it) }
                    if (instruction.opcode == Opcode.Select) {
                        val condition = operands[0].constant
                        if (condition is ConstantScalar) {
                            update(instruction, operands[if (condition.asBoolean) 1 else 2])
                            return
                        }
                    }
                    if (operands.any { it.isOverdefined }) {
                        update(instruction, LatticeValue.Overdefined)
                        return
                    }
                    if (operands.any { it.isUnknown }) return
                    val folded = ConstantFolding.fold(instruction, operands.map { it.constant!! })
                    update(instruction, if (folded != null) LatticeValue.of(folded) else LatticeValue.Overdefined)
                }
            }
        }

        executable.add(function.entry)
        blockWork.add(function.entry)
        while (blockWork.isNotEmpty() || valueWork.isNotEmpty()) {
            while (valueWork.isNotEmpty()) visit(valueWork.removeFirst())
            if (blockWork.isNotEmpty()) blockWork.removeFirst().instructions.toList().forEach { visit(it) }
        }

        var changed = false
        val replacements = HashMap<Value, Value>()
        for ((instruction, value) in lattice) {
            val constant = value.constant ?: continue
            if (instruction.block in executable && Purity.isFoldable(instruction)) replacements[instruction] = constant
        }
        if (replacements.isNotEmpty()) {
            IrRewriter.replace(function, replacements)
            changed = true
        }
        for (block in function.blocks) {
            if (block !in executable) continue
            val terminator = block.terminator ?: continue
            if (terminator.opcode != Opcode.CondBranch && terminator.opcode != Opcode.Switch) continue
            if (block.construct == ConstructKind.Loop) continue
            val taken = block.successors.distinct().filter { (block to it) in edges }
            if (taken.size != 1) continue
            terminator.opcode = Opcode.Branch
            terminator.operands.clear()
            terminator.literals = Instruction.EMPTY
            terminator.targets.clear()
            terminator.targets.add(taken[0])
            if (block.construct == ConstructKind.Selection) block.clearConstruct()
            changed = true
        }
        return changed
    }

    private fun switchTarget(instruction: Instruction, selector: ConstantScalar): Block {
        val type = selector.type as? IrInt ?: return instruction.targets[0]
        val index = instruction.literals.indexOfFirst { ConstantScalar.int(type, it.toLong()).bits == selector.bits }
        return if (index < 0) instruction.targets[0] else instruction.targets[index + 1]
    }
}
