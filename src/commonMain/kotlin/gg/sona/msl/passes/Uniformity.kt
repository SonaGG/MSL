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
        var changed = true
        while (changed) {
            changed = false
            val joins = joins(divergentBranches)
            val temporal = temporal(cfg, divergentBranches)
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
                    changed = true
                }
            }
        }
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

    private fun joins(branches: Set<Block>): Set<Block> {
        val result = HashSet<Block>()
        for (branch in branches) {
            val reached = HashMap<Block, Int>()
            for (successor in branch.successors.distinct()) {
                val seen = HashSet<Block>()
                val work = ArrayDeque<Block>()
                work.add(successor)
                while (work.isNotEmpty()) {
                    val block = work.removeLast()
                    if (!seen.add(block)) continue
                    reached[block] = (reached[block] ?: 0) + 1
                    work.addAll(block.successors)
                }
            }
            reached.filterValues { it > 1 }.keys.let(result::addAll)
        }
        return result
    }

    private fun temporal(cfg: ControlFlowGraph, branches: Set<Block>): Set<Block> {
        val result = HashSet<Block>()
        for (header in cfg.reversePostorder) {
            if (header.construct != ConstructKind.Loop) continue
            val body = loopBlocks(header, cfg)
            if (body.none { it in branches }) continue
            val exits = HashSet<Block>()
            for (block in body) for (successor in block.successors) if (successor !in body) exits.add(successor)
            val work = ArrayDeque(exits)
            while (work.isNotEmpty()) {
                val block = work.removeLast()
                if (!result.add(block)) continue
                work.addAll(block.successors)
            }
            result.addAll(body)
        }
        return result
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
