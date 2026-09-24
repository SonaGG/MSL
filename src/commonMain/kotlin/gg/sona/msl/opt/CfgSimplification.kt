package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object CfgSimplification {
    fun run(function: IrFunction): Boolean {
        var changed = false
        var progress = true
        while (progress) {
            progress = foldBranches(function) or eliminateTrivialBlocks(function) or threadJumps(function)
            changed = changed or progress
        }
        return changed
    }

    private fun foldBranches(function: IrFunction): Boolean {
        var changed = false
        for (block in function.blocks) {
            val terminator = block.terminator ?: continue
            if (block.construct == ConstructKind.Loop) continue
            when (terminator.opcode) {
                Opcode.CondBranch -> {
                    val condition = terminator.operands[0] as? Instruction
                    if (condition != null && condition.opcode == Opcode.LogicalNot) {
                        terminator.operands[0] = condition.operands[0]
                        terminator.targets.reverse()
                        changed = true
                    }
                    if (terminator.targets[0] === terminator.targets[1] && unconditional(block, terminator)) changed = true
                }

                Opcode.Switch -> if (terminator.targets.distinct().size == 1 && unconditional(block, terminator)) changed = true
            }
        }
        return changed
    }

    private fun unconditional(block: Block, terminator: Instruction): Boolean {
        val target = terminator.targets[0]
        for (phi in target.phis) {
            val incoming = phi.targets.indices.filter { phi.targets[it] === block }.map { phi.operands[it] }
            if (incoming.distinct().size > 1) return false
        }
        for (phi in target.phis) {
            val indices = phi.targets.indices.filter { phi.targets[it] === block }
            for (index in indices.drop(1).asReversed()) {
                phi.targets.removeAt(index)
                phi.operands.removeAt(index)
            }
        }
        val branch = Instruction(Opcode.Branch, IrVoid).also { it.targets.add(target) }
        block.instructions[block.instructions.size - 1] = branch
        branch.block = block
        if (block.construct == ConstructKind.Selection) block.clearConstruct()
        return true
    }

    private fun eliminateTrivialBlocks(function: IrFunction): Boolean {
        var changed = false
        while (true) {
            val blocks = StructuredBlocks(function)
            val block = function.blocks.firstOrNull { trivial(it, function, blocks) } ?: return changed
            val target = block.terminator!!.targets[0]
            val predecessors = blocks.predecessorsOf(block)
            for (phi in target.phis) {
                val index = phi.targets.indexOf(block)
                val value = phi.operands[index]
                phi.targets.removeAt(index)
                phi.operands.removeAt(index)
                for (predecessor in predecessors) {
                    if (predecessor in phi.targets) continue
                    phi.targets.add(predecessor)
                    phi.operands.add(value)
                }
            }
            for (predecessor in predecessors) predecessor.terminator!!.replaceTarget(block, target)
            function.blocks.remove(block)
            changed = true
        }
    }

    private fun trivial(block: Block, function: IrFunction, blocks: StructuredBlocks): Boolean {
        if (block === function.entry || block.instructions.size != 1 || block.construct != ConstructKind.None) return false
        val terminator = block.terminator ?: return false
        if (terminator.opcode != Opcode.Branch || block in blocks.structural) return false
        val target = terminator.targets[0]
        if (target === block || target.construct == ConstructKind.Loop) return false
        val predecessors = blocks.predecessorsOf(block)
        if (predecessors.isEmpty()) return false
        for (predecessor in predecessors) {
            val edges = predecessor.terminator!!.targets
            if (predecessor.terminator!!.opcode == Opcode.Switch && (target in edges || edges.count { it === block } > 1)) return false
        }
        for (phi in target.phis) {
            val value = phi.operands[phi.targets.indexOf(block)]
            for (predecessor in predecessors) {
                val existing = phi.targets.indexOf(predecessor)
                if (existing >= 0 && phi.operands[existing] !== value) return false
            }
        }
        return true
    }

    private fun threadJumps(function: IrFunction): Boolean {
        val blocks = StructuredBlocks(function)
        for (block in function.blocks) {
            val terminator = block.terminator ?: continue
            if (block.construct != ConstructKind.Selection || terminator.opcode != Opcode.CondBranch) continue
            if (block.instructions.size != block.phis.size + 1) continue
            val condition = terminator.operands[0] as? Instruction ?: continue
            if (condition.opcode != Opcode.Phi || condition.block !== block) continue
            if (condition.operands.any { it !is ConstantScalar }) continue
            val header = function.blocks.singleOrNull { it.merge === block && it.construct == ConstructKind.Selection } ?: continue
            if (function.blocks.any { it.continueTarget === block }) continue
            val merge = block.merge ?: continue
            if (function.blocks.count { it.merge === merge } != 1 || function.blocks.any { it.continueTarget === merge }) continue
            if (function.instructions().any { user -> user !== terminator && user.operands.any { it is Instruction && it.block === block } }) continue
            thread(function, block, header, merge, condition, terminator)
            return true
        }
        return false
    }

    private fun thread(function: IrFunction, block: Block, header: Block, merge: Block, condition: Instruction, terminator: Instruction) {
        val routes = condition.targets.indices.map { index ->
            val taken = (condition.operands[index] as ConstantScalar).asBoolean
            condition.targets[index] to terminator.targets[if (taken) 0 else 1]
        }
        for (target in terminator.targets.distinct()) {
            for (phi in target.phis) {
                val index = phi.targets.indexOf(block)
                if (index < 0) continue
                val value: Value = phi.operands[index]
                phi.targets.removeAt(index)
                phi.operands.removeAt(index)
                for ((predecessor, destination) in routes) {
                    if (destination !== target || predecessor in phi.targets) continue
                    phi.targets.add(predecessor)
                    phi.operands.add(if (value is IrConstant || value !is Instruction || value.block !== block) value else continue)
                }
            }
        }
        for ((predecessor, destination) in routes) predecessor.terminator!!.replaceTarget(block, destination)
        header.merge = merge
        block.clearConstruct()
        function.blocks.remove(block)
    }
}
