package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.IrFunction

class ControlFlowGraph(val function: IrFunction) {
    val predecessors = HashMap<Block, MutableList<Block>>()
    val reversePostorder = ArrayList<Block>()
    val reachable = HashSet<Block>()
    private val order = HashMap<Block, Int>()
    private val immediateDominators = HashMap<Block, Block>()
    private val children = HashMap<Block, MutableList<Block>>()

    init {
        val postorder = ArrayList<Block>()
        val stack = ArrayList<Pair<Block, Int>>()
        stack.add(function.entry to 0)
        reachable.add(function.entry)
        while (stack.isNotEmpty()) {
            val (block, index) = stack.removeAt(stack.size - 1)
            val successors = block.successors.distinct()
            if (index < successors.size) {
                stack.add(block to index + 1)
                val successor = successors[index]
                if (reachable.add(successor)) stack.add(successor to 0)
            } else {
                postorder.add(block)
            }
        }
        reversePostorder.addAll(postorder.asReversed())
        reversePostorder.forEachIndexed { index, block -> order[block] = index }
        for (block in reversePostorder) {
            for (successor in block.successors.distinct()) {
                predecessors.getOrPut(successor) { ArrayList() }.add(block)
            }
        }
        computeDominators()
    }

    fun predecessorsOf(block: Block): List<Block> = predecessors[block] ?: emptyList()

    private fun computeDominators() {
        val entry = function.entry
        immediateDominators[entry] = entry
        var changed = true
        while (changed) {
            changed = false
            for (block in reversePostorder) {
                if (block === entry) continue
                var candidate: Block? = null
                for (predecessor in predecessorsOf(block)) {
                    if (immediateDominators[predecessor] == null) continue
                    candidate = if (candidate == null) predecessor else intersect(predecessor, candidate)
                }
                if (candidate != null && immediateDominators[block] !== candidate) {
                    immediateDominators[block] = candidate
                    changed = true
                }
            }
        }
        for ((block, dominator) in immediateDominators) {
            if (block !== dominator) children.getOrPut(dominator) { ArrayList() }.add(block)
        }
        for (list in children.values) list.sortBy { order[it] }
    }

    private fun intersect(first: Block, second: Block): Block {
        var a = first
        var b = second
        while (a !== b) {
            while (order.getValue(a) > order.getValue(b)) a = immediateDominators.getValue(a)
            while (order.getValue(b) > order.getValue(a)) b = immediateDominators.getValue(b)
        }
        return a
    }

    fun immediateDominator(block: Block): Block? = immediateDominators[block]?.takeIf { it !== block }

    fun dominatorChildren(block: Block): List<Block> = children[block] ?: emptyList()

    fun dominates(dominator: Block, block: Block): Boolean {
        var current: Block? = block
        while (current != null) {
            if (current === dominator) return true
            current = immediateDominator(current)
        }
        return false
    }

    fun dominanceFrontiers(): Map<Block, Set<Block>> {
        val frontiers = HashMap<Block, MutableSet<Block>>()
        for (block in reversePostorder) {
            val predecessors = predecessorsOf(block)
            if (predecessors.size < 2) continue
            val idom = immediateDominators[block] ?: continue
            for (predecessor in predecessors) {
                var runner: Block = predecessor
                while (runner !== idom) {
                    frontiers.getOrPut(runner) { LinkedHashSet() }.add(block)
                    runner = immediateDominators[runner] ?: break
                }
            }
        }
        return frontiers
    }
}
