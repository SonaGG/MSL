package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object BlockMerging {
    fun run(function: IrFunction): Boolean {
        val blocks = StructuredBlocks(function)
        val replacements = HashMap<Value, Value>()
        val removed = HashSet<Block>()
        for (block in function.blocks.toList()) {
            if (block in removed) continue
            while (true) {
                val successor = mergeable(block, blocks) ?: break
                merge(block, successor, blocks, replacements)
                removed.add(successor)
            }
        }
        if (removed.isEmpty()) return false
        function.blocks.removeAll(removed)
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun mergeable(block: Block, blocks: StructuredBlocks): Block? {
        if (block.construct != ConstructKind.None) return null
        val terminator = block.terminator ?: return null
        if (terminator.opcode != Opcode.Branch) return null
        val successor = terminator.targets[0]
        if (successor === block || successor === block.function?.entry) return null
        if (successor in blocks.structural) return null
        if (successor.construct == ConstructKind.Loop) return null
        if (blocks.predecessorsOf(successor) != listOf(block)) return null
        return successor
    }

    private fun merge(block: Block, successor: Block, blocks: StructuredBlocks, replacements: MutableMap<Value, Value>) {
        for (phi in successor.phis) replacements[phi] = phi.operands[phi.targets.indexOf(block)]
        block.instructions.removeAt(block.instructions.size - 1)
        for (instruction in successor.instructions) {
            if (instruction in replacements) continue
            instruction.block = block
            block.instructions.add(instruction)
        }
        block.copyConstructFrom(successor)
        for (next in successor.successors.distinct()) {
            for (phi in next.phis) phi.replaceTarget(successor, block)
            val predecessors = blocks.predecessors[next] ?: continue
            for (i in predecessors.indices) if (predecessors[i] === successor) predecessors[i] = block
        }
        blocks.predecessors.remove(successor)
    }
}
