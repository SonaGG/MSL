package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object BlockMerging {
    fun run(function: IrFunction): Boolean {
        var changed = false
        while (true) {
            val blocks = StructuredBlocks(function)
            val pair = function.blocks.firstNotNullOfOrNull { block -> mergeable(block, blocks)?.let { block to it } } ?: return changed
            merge(function, pair.first, pair.second)
            changed = true
        }
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

    private fun merge(function: IrFunction, block: Block, successor: Block) {
        val replacements = HashMap<Value, Value>()
        for (phi in successor.phis) replacements[phi] = phi.operands[phi.targets.indexOf(block)]
        block.instructions.removeAt(block.instructions.size - 1)
        for (instruction in successor.instructions) {
            if (instruction in replacements) continue
            instruction.block = block
            block.instructions.add(instruction)
        }
        block.copyConstructFrom(successor)
        for (other in function.blocks) {
            for (phi in other.phis) phi.replaceTarget(successor, block)
            if (other.merge === successor) other.merge = block
            if (other.continueTarget === successor) other.continueTarget = block
        }
        function.blocks.remove(successor)
        IrRewriter.replace(function, replacements)
    }
}
