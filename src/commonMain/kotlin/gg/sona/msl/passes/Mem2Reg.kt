package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value

object Mem2Reg {
    fun run(function: IrFunction) {
        val uses = Uses(function)
        val candidates = function.entry.instructions.filter { it.opcode == Opcode.Variable && isPromotable(it, uses) }
        if (candidates.isEmpty()) return
        for (variable in candidates) rewritePartialAccesses(variable, uses)
        val replacements = HashMap<Value, Value>()
        val cfg = ControlFlowGraph(function)
        val frontiers = cfg.dominanceFrontiers()
        val phiOwners = HashMap<Instruction, Instruction>()
        val candidateSet = candidates.toHashSet()
        val stores = storeBlocks(cfg, candidateSet)
        for (variable in candidates) placePhis(variable, stores[variable] ?: LinkedHashSet(), frontiers, phiOwners)
        val removed = HashSet<Instruction>()
        rename(function.entry, cfg, candidateSet, phiOwners, HashMap(), replacements, removed)
        for (block in function.blocks) block.instructions.removeAll { it in removed || it in candidateSet }
        for (block in function.blocks) {
            if (block !in cfg.reachable) continue
            for (instruction in block.instructions) {
                for (i in instruction.operands.indices) instruction.operands[i] = resolve(instruction.operands[i], replacements)
            }
        }
    }

    private fun resolve(value: Value, replacements: Map<Value, Value>): Value {
        var current = value
        var steps = 0
        while (true) {
            val next = replacements[current] ?: return current
            current = next
            if (++steps > 10000) error("replacement cycle")
        }
    }

    private fun containsOpaque(type: IrType): Boolean = when (type) {
        is IrImage, is IrSampler -> true
        is IrArray -> type.isRuntime || containsOpaque(type.element)
        is IrStruct -> type.members.any { containsOpaque(it.type) }
        else -> false
    }

    private fun isPromotable(variable: Instruction, uses: Uses): Boolean {
        val valueType = (variable.type as IrPointer).pointee
        if (containsOpaque(valueType)) return false
        for (user in uses.of(variable)) {
            when (user.opcode) {
                Opcode.Load -> Unit
                Opcode.Store -> if (user.operands[1] === variable) return false
                Opcode.AccessChain -> {
                    if (user.operands[0] !== variable) return false
                    if (user.operands.drop(1).any { it !is ConstantScalar }) return false
                    for (chainUser in uses.of(user)) {
                        when (chainUser.opcode) {
                            Opcode.Load -> Unit
                            Opcode.Store -> if (chainUser.operands[1] === user) return false
                            else -> return false
                        }
                    }
                }

                else -> return false
            }
        }
        return true
    }

    private fun rewritePartialAccesses(variable: Instruction, uses: Uses) {
        for (chain in uses.of(variable).filter { it.opcode == Opcode.AccessChain }) {
            val path = chain.operands.drop(1).map { (it as ConstantScalar).bits.toInt() }.toIntArray()
            for (user in uses.of(chain)) {
                val block = user.block!!
                val index = block.instructions.indexOf(user)
                val whole = Instruction(Opcode.Load, (variable.type as IrPointer).pointee, listOf(variable))
                whole.block = block
                block.instructions.add(index, whole)
                if (user.opcode == Opcode.Load) {
                    user.opcode = Opcode.CompositeExtract
                    user.operands.clear()
                    user.operands.add(whole)
                    user.literals = path
                } else {
                    val value = user.operands[1]
                    val insert = Instruction(Opcode.CompositeInsert, whole.type, listOf(value, whole))
                    insert.literals = path
                    insert.block = block
                    block.instructions.add(index + 1, insert)
                    user.operands[0] = variable
                    user.operands[1] = insert
                }
            }
            chain.block?.instructions?.remove(chain)
        }
    }

    private fun storeBlocks(cfg: ControlFlowGraph, candidates: Set<Instruction>): Map<Instruction, LinkedHashSet<Block>> {
        val stores = HashMap<Instruction, LinkedHashSet<Block>>()
        for (block in cfg.reversePostorder) {
            for (instruction in block.instructions) {
                if (instruction.opcode != Opcode.Store) continue
                val variable = instruction.operands[0] as? Instruction ?: continue
                if (variable in candidates) stores.getOrPut(variable) { LinkedHashSet() }.add(block)
            }
        }
        return stores
    }

    private fun placePhis(
        variable: Instruction,
        definitions: LinkedHashSet<Block>,
        frontiers: Map<Block, Set<Block>>,
        phiOwners: HashMap<Instruction, Instruction>,
    ) {
        val valueType = (variable.type as IrPointer).pointee
        val placed = HashSet<Block>()
        val worklist = ArrayDeque(definitions)
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            for (frontier in frontiers[block] ?: emptySet()) {
                if (!placed.add(frontier)) continue
                val phi = Instruction(Opcode.Phi, valueType)
                phi.block = frontier
                frontier.instructions.add(0, phi)
                phiOwners[phi] = variable
                if (frontier !in definitions) {
                    definitions.add(frontier)
                    worklist.add(frontier)
                }
            }
        }
    }

    private fun rename(
        entry: Block,
        cfg: ControlFlowGraph,
        candidates: Set<Instruction>,
        phiOwners: Map<Instruction, Instruction>,
        initial: HashMap<Instruction, Value>,
        replacements: HashMap<Value, Value>,
        removed: HashSet<Instruction>,
    ) {
        val stack = ArrayDeque<RenameFrame>()
        stack.add(RenameFrame(entry, initial))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val block = frame.block
            val values = HashMap(frame.values)
            for (instruction in block.instructions) {
                when (instruction.opcode) {
                    Opcode.Phi -> phiOwners[instruction]?.let { values[it] = instruction }
                    Opcode.Load -> {
                        val variable = instruction.operands[0] as? Instruction ?: continue
                        if (variable !in candidates) continue
                        val current = values[variable] ?: Undef(instruction.type)
                        replacements[instruction] = current
                        removed.add(instruction)
                    }

                    Opcode.Store -> {
                        val variable = instruction.operands[0] as? Instruction ?: continue
                        if (variable !in candidates) continue
                        values[variable] = instruction.operands[1]
                        removed.add(instruction)
                    }

                    else -> Unit
                }
            }
            for (successor in block.successors.distinct()) {
                for (phi in successor.phis) {
                    val variable = phiOwners[phi] ?: continue
                    phi.operands.add(values[variable] ?: Undef(phi.type))
                    phi.targets.add(block)
                }
            }
            for (child in cfg.dominatorChildren(block).asReversed()) stack.add(RenameFrame(child, values))
        }
    }
}
