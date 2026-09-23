package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.IrFunction

class StructuredBlocks(function: IrFunction) {
    val predecessors = HashMap<Block, MutableList<Block>>()
    val structural = HashSet<Block>()

    init {
        for (block in function.blocks) {
            for (successor in block.successors.distinct()) predecessors.getOrPut(successor) { ArrayList() }.add(block)
            block.merge?.let { structural.add(it) }
            block.continueTarget?.let { structural.add(it) }
        }
    }

    fun predecessorsOf(block: Block): List<Block> = predecessors[block] ?: emptyList()
}
