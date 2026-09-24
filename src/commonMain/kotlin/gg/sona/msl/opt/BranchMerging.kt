package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.ControlFlowGraph

object BranchMerging {
    fun run(function: IrFunction): Boolean {
        val cfg = ControlFlowGraph(function)
        var changed = false
        for (header in cfg.reversePostorder) {
            if (header.construct != ConstructKind.Selection) continue
            val terminator = header.terminator ?: continue
            if (terminator.opcode != Opcode.CondBranch) continue
            val (first, second) = terminator.targets
            val merge = header.merge ?: continue
            if (first === second || first === merge || second === merge) continue
            if (cfg.predecessorsOf(first) != listOf(header) || cfg.predecessorsOf(second) != listOf(header)) continue
            if (first.construct != ConstructKind.None || second.construct != ConstructKind.None) continue
            changed = hoist(header, first, second) or changed
            changed = sinkStores(cfg, first, second, merge) or changed
        }
        return changed
    }

    private fun hoistable(instruction: Instruction, arm: Block): Boolean {
        if (instruction.opcode == Opcode.Phi || instruction.isTerminator) return false
        val allowed = when (instruction.opcode) {
            Opcode.Load -> Purity.isReadOnly(instruction.operands[0])
            Opcode.AccessChain, Opcode.PtrOffset -> true
            else -> Purity.isFoldable(instruction)
        }
        return allowed && instruction.operands.none { it is Instruction && it.block === arm }
    }

    private fun same(a: Instruction, b: Instruction): Boolean =
        a.opcode == b.opcode && a.type == b.type && a.intrinsic == b.intrinsic && a.literals.contentEquals(b.literals) &&
            a.operands.size == b.operands.size && a.operands.indices.all { a.operands[it] === b.operands[it] || a.operands[it] is IrConstant && a.operands[it] == b.operands[it] }

    private fun hoist(header: Block, first: Block, second: Block): Boolean {
        var changed = false
        var progress = true
        while (progress) {
            progress = false
            for (candidate in first.instructions.toList()) {
                if (!hoistable(candidate, first)) continue
                val twin = second.instructions.firstOrNull { hoistable(it, second) && same(candidate, it) } ?: continue
                first.instructions.remove(candidate)
                IrRewriter.insertBefore(header.terminator!!, candidate)
                second.instructions.remove(twin)
                replace(second, twin, candidate)
                progress = true
                changed = true
                break
            }
        }
        return changed
    }

    private fun replace(block: Block, old: Value, new: Value) {
        val function = block.function ?: return
        IrRewriter.replace(function, mapOf(old to new))
    }

    private fun sinkStores(cfg: ControlFlowGraph, first: Block, second: Block, merge: Block): Boolean {
        if (cfg.predecessorsOf(merge).toSet() != setOf(first, second)) return false
        val a = lastStore(first) ?: return false
        val b = lastStore(second) ?: return false
        val pointer = a.operands[0]
        if (pointer !== b.operands[0] || pointer is Instruction && (pointer.block === first || pointer.block === second)) return false
        if (a.operands[1].type != b.operands[1].type) return false
        first.instructions.remove(a)
        second.instructions.remove(b)
        val phi = Instruction(Opcode.Phi, a.operands[1].type, listOf(a.operands[1], b.operands[1]))
        phi.targets.add(first)
        phi.targets.add(second)
        phi.block = merge
        val phis = merge.phis.size
        merge.instructions.add(phis, phi)
        a.operands[1] = phi
        a.block = merge
        merge.instructions.add(phis + 1, a)
        return true
    }

    private fun lastStore(block: Block): Instruction? {
        val instructions = block.instructions
        if (instructions.size < 2) return null
        val terminator = instructions.last()
        if (terminator.opcode != Opcode.Branch) return null
        return instructions[instructions.size - 2].takeIf { it.opcode == Opcode.Store }
    }
}
