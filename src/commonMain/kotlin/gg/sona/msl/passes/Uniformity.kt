package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Parameter
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value

class Uniformity(function: IrFunction) {
    private val divergent = HashSet<Value>()

    init {
        val cfg = ControlFlowGraph(function)
        val divergentBranches = HashSet<Block>()
        val joins = HashSet<Block>()
        val temporal = HashSet<Block>()
        val loops = cfg.reversePostorder.filter { it.construct == ConstructKind.Loop }
        val bodies = HashMap<Block, Set<Block>>()
        val pending = ArrayList<Block>()
        var changed = true
        while (changed) {
            changed = false
            if (pending.isNotEmpty()) {
                pending.forEach { addJoins(it, joins) }
                for (header in loops) {
                    if (header in temporal) continue
                    val body = bodies.getOrPut(header) { loopBlocks(header, cfg) }
                    if (pending.none { it in body }) continue
                    addTemporal(body, temporal)
                }
                pending.clear()
            }
            for (block in cfg.reversePostorder) {
                for (instruction in block.instructions) {
                    if (instruction in divergent) continue
                    val isDivergent = when {
                        instruction.opcode == Opcode.Phi -> block in joins || instruction.operands.any(::isDivergent)
                        block in temporal -> true
                        else -> source(instruction)
                    }
                    if (isDivergent) {
                        divergent.add(instruction)
                        changed = true
                    }
                }
                val terminator = block.terminator ?: continue
                if ((terminator.opcode == Opcode.CondBranch || terminator.opcode == Opcode.Switch) && block !in divergentBranches &&
                    isDivergent(terminator.operands[0])
                ) {
                    divergentBranches.add(block)
                    pending.add(block)
                    changed = true
                }
            }
        }
    }

    fun inherit(source: Value, derived: Value) {
        if (isDivergent(source)) divergent.add(derived)
    }

    fun isUniform(value: Value): Boolean = !isDivergent(value)

    private fun isDivergent(value: Value): Boolean = value in divergent || value is Parameter

    private fun source(instruction: Instruction): Boolean = when (instruction.opcode) {
        Opcode.Load -> load(instruction)
        Opcode.Call, Opcode.Variable -> true
        Opcode.Intrinsic -> when (instruction.intrinsic!!) {
            in SUBGROUP_UNIFORM -> false
            in ALWAYS_DIVERGENT -> true
            else -> instruction.operands.any(::isDivergent) || instruction.intrinsic!!.name.startsWith("Atomic")
        }

        else -> instruction.operands.any(::isDivergent)
    }

    private fun load(instruction: Instruction): Boolean {
        val pointer = instruction.operands[0]
        var root = pointer
        while (root is Instruction && (root.opcode == Opcode.AccessChain || root.opcode == Opcode.PtrOffset)) root = root.operands[0]
        val global = root as? GlobalVariable ?: return true
        if (isDivergent(pointer)) return true
        return when (global.storage) {
            StorageClass.Input -> global.interfaceInfo?.builtin !in UNIFORM_BUILTINS
            StorageClass.Uniform, StorageClass.PushConstant, StorageClass.UniformConstant -> false
            StorageClass.StorageBuffer -> global.resource?.readOnly != true
            StorageClass.Private -> !global.isConstant
            else -> true
        }
    }

    private fun addJoins(branch: Block, joins: MutableSet<Block>) {
        val reached = HashMap<Block, Int>()
        for (successor in branch.successors.distinct()) {
            val seen = HashSet<Block>()
            val work = ArrayDeque<Block>()
            work.add(successor)
            while (work.isNotEmpty()) {
                val block = work.removeLast()
                if (block in joins || !seen.add(block)) continue
                reached[block] = (reached[block] ?: 0) + 1
                work.addAll(block.successors)
            }
        }
        for ((block, count) in reached) if (count > 1) joins.add(block)
    }

    private fun addTemporal(body: Set<Block>, temporal: MutableSet<Block>) {
        val work = ArrayDeque<Block>()
        for (block in body) for (successor in block.successors) if (successor !in body) work.add(successor)
        while (work.isNotEmpty()) {
            val block = work.removeLast()
            if (!temporal.add(block)) continue
            work.addAll(block.successors)
        }
        temporal.addAll(body)
    }

    private fun loopBlocks(header: Block, cfg: ControlFlowGraph): Set<Block> {
        val latch = header.continueTarget ?: return setOf(header)
        val body = hashSetOf(header)
        val work = ArrayDeque<Block>()
        if (body.add(latch)) work.add(latch)
        while (work.isNotEmpty()) {
            val block = work.removeFirst()
            for (predecessor in cfg.predecessorsOf(block)) if (body.add(predecessor)) work.add(predecessor)
        }
        return body
    }

    private companion object {
        val UNIFORM_BUILTINS = setOf(
            BuiltinVariable.BaseVertex, BuiltinVariable.BaseInstance, BuiltinVariable.WorkgroupId, BuiltinVariable.NumWorkgroups,
            BuiltinVariable.SubgroupSize, BuiltinVariable.NumSubgroups, BuiltinVariable.SubgroupId,
        )

        val SUBGROUP_UNIFORM = setOf(
            Intrinsic.SimdBroadcast, Intrinsic.SimdBroadcastFirst, Intrinsic.SimdSum, Intrinsic.SimdProduct, Intrinsic.SimdMin,
            Intrinsic.SimdMax, Intrinsic.SimdAnd, Intrinsic.SimdOr, Intrinsic.SimdXor, Intrinsic.SimdBallot, Intrinsic.SimdAll,
            Intrinsic.SimdAny, Intrinsic.SimdActiveThreadsMask,
        )

        val ALWAYS_DIVERGENT = setOf(
            Intrinsic.Dfdx, Intrinsic.Dfdy, Intrinsic.Fwidth, Intrinsic.SimdIsFirst, Intrinsic.SimdShuffle, Intrinsic.SimdShuffleUp,
            Intrinsic.SimdShuffleDown, Intrinsic.SimdShuffleXor, Intrinsic.SimdPrefixInclusiveSum, Intrinsic.SimdPrefixInclusiveProduct,
            Intrinsic.SimdPrefixExclusiveSum, Intrinsic.SimdPrefixExclusiveProduct, Intrinsic.QuadBroadcast, Intrinsic.QuadShuffle,
            Intrinsic.QuadShuffleXor, Intrinsic.TextureCalculateLod,
        )
    }
}
