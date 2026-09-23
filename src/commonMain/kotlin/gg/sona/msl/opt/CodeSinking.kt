package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.passes.ControlFlowGraph
import gg.sona.msl.passes.Uses

object CodeSinking {
    fun run(function: IrFunction): Boolean {
        val cfg = ControlFlowGraph(function)
        val loops = HashMap<Block, MutableSet<Block>>()
        for (header in cfg.reversePostorder) {
            if (header.construct != ConstructKind.Loop) continue
            val body = LoopInvariantCodeMotion.loopBlocks(header, cfg) ?: return false
            for (block in body) loops.getOrPut(block) { HashSet() }.add(header)
        }
        val uses = Uses(function)
        var changed = false
        for (block in cfg.reversePostorder.asReversed()) {
            for (instruction in block.instructions.asReversed().toList()) {
                if (!movable(instruction)) continue
                val users = uses.of(instruction)
                if (users.isEmpty()) continue
                var target: Block? = null
                for (user in users) {
                    val site = if (user.opcode == Opcode.Phi) user.targets[user.operands.indexOf(instruction)] else user.block!!
                    target = if (target == null) site else commonDominator(cfg, target, site)
                }
                if (target == null || target === block || !cfg.dominates(block, target)) continue
                if ((loops[target] ?: emptySet<Block>()) != (loops[block] ?: emptySet<Block>())) continue
                block.instructions.remove(instruction)
                val first = target.instructions.indexOfFirst { it.opcode != Opcode.Phi && (it in users || it.isTerminator) }
                instruction.block = target
                target.instructions.add(first, instruction)
                changed = true
            }
        }
        return changed
    }

    private fun commonDominator(cfg: ControlFlowGraph, first: Block, second: Block): Block {
        var candidate: Block? = first
        while (candidate != null && !cfg.dominates(candidate, second)) candidate = cfg.immediateDominator(candidate)
        return candidate ?: first
    }

    private fun movable(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Load -> Purity.isReadOnly(instruction.operands[0])
        Opcode.Phi, Opcode.Variable, Opcode.Store, Opcode.Call -> false
        Opcode.AccessChain -> true
        else -> !instruction.isTerminator && Purity.isFoldable(instruction)
    }
}
