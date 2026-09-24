package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object RegisterPressure {
    fun peak(function: IrFunction): Int {
        val cfg = ControlFlowGraph(function)
        val liveOut = HashMap<Block, Set<Instruction>>()
        val liveIn = HashMap<Block, Set<Instruction>>()
        var changed = true
        while (changed) {
            changed = false
            for (block in cfg.reversePostorder.asReversed()) {
                val out = HashSet<Instruction>()
                for (successor in block.successors) {
                    liveIn[successor]?.let(out::addAll)
                    for (phi in successor.phis) {
                        val index = phi.targets.indexOf(block)
                        (phi.operands.getOrNull(index) as? Instruction)?.let(out::add)
                    }
                }
                val live = HashSet(out)
                for (instruction in block.instructions.asReversed()) {
                    live.remove(instruction)
                    if (instruction.opcode != Opcode.Phi) instruction.operands.filterIsInstance<Instruction>().forEach(live::add)
                }
                if (out != liveOut[block] || live != liveIn[block]) {
                    liveOut[block] = out
                    liveIn[block] = live
                    changed = true
                }
            }
        }
        var peak = 0
        for (block in cfg.reversePostorder) {
            val live = HashSet(liveOut[block] ?: emptySet())
            peak = maxOf(peak, live.sumOf(::lanes))
            for (instruction in block.instructions.asReversed()) {
                live.remove(instruction)
                if (instruction.opcode != Opcode.Phi) instruction.operands.filterIsInstance<Instruction>().forEach(live::add)
                peak = maxOf(peak, live.sumOf(::lanes))
            }
        }
        return peak
    }

    private fun lanes(value: Value): Int = when (val type = value.type) {
        is IrScalar -> 1
        is IrVector -> type.count
        is IrMatrix -> type.columns * type.rows
        else -> 0
    }
}
