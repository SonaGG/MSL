package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object Inliner {
    fun run(module: IrModule) {
        val entries = module.entryPoints.map { it.function }.toSet()
        for (function in entries) {
            while (true) {
                val call = function.instructions().firstOrNull { it.opcode == Opcode.Call } ?: break
                inline(function, call)
            }
        }
        module.functions.retainAll { it in entries }
    }

    private fun inline(function: IrFunction, call: Instruction) {
        val block = call.block!!
        val callee = call.callee!!
        val index = block.instructions.indexOf(call)
        val post = Block("${block.name}.after")
        post.function = function
        function.blocks.add(function.blocks.indexOf(block) + 1, post)
        val tail = block.instructions.subList(index + 1, block.instructions.size).toList()
        block.instructions.subList(index, block.instructions.size).clear()
        for (instruction in tail) {
            instruction.block = post
            post.instructions.add(instruction)
        }
        post.copyConstructFrom(block)
        block.clearConstruct()
        for (successor in post.successors.distinct()) {
            for (phi in successor.phis) phi.replaceTarget(block, post)
        }
        val mapping = HashMap<Value, Value>()
        callee.parameters.forEachIndexed { i, parameter -> mapping[parameter] = call.operands[i] }
        val blockMapping = HashMap<Block, Block>()
        for (source in callee.blocks) {
            val clone = Block("${callee.name}.${source.name}")
            clone.function = function
            blockMapping[source] = clone
        }
        val clones = ArrayList<Instruction>()
        val returns = ArrayList<Pair<Instruction, Block>>()
        for (source in callee.blocks) {
            val target = blockMapping.getValue(source)
            target.construct = source.construct
            target.merge = source.merge?.let { blockMapping[it] }
            target.continueTarget = source.continueTarget?.let { blockMapping[it] }
            for (instruction in source.instructions) {
                val clone = Instruction(instruction.opcode, instruction.type, instruction.operands)
                clone.literals = instruction.literals
                clone.intrinsic = instruction.intrinsic
                clone.callee = instruction.callee
                clone.name = instruction.name
                clone.targets.addAll(instruction.targets.map { blockMapping.getValue(it) })
                mapping[instruction] = clone
                clones.add(clone)
                if (instruction.opcode == Opcode.Variable) {
                    clone.block = function.entry
                    function.entry.instructions.add(0, clone)
                } else {
                    clone.block = target
                    target.instructions.add(clone)
                }
                if (instruction.opcode == Opcode.Return) returns.add(clone to target)
            }
        }
        for (clone in clones) clone.replaceOperands(mapping)
        var result: Value? = null
        val incoming = ArrayList<Pair<Value, Block>>()
        for ((instruction, target) in returns) {
            val value = instruction.operands.firstOrNull()
            if (value != null) incoming.add(value to target)
            target.instructions.remove(instruction)
            val branch = Instruction(Opcode.Branch, IrVoid)
            branch.targets.add(post)
            branch.block = target
            target.instructions.add(branch)
        }
        if (call.type != IrVoid) {
            result = if (incoming.size == 1) {
                incoming[0].first
            } else {
                val phi = Instruction(Opcode.Phi, call.type, incoming.map { it.first })
                phi.targets.addAll(incoming.map { it.second })
                phi.block = post
                post.instructions.add(0, phi)
                phi
            }
        }
        val calleeEntry = blockMapping.getValue(callee.entry)
        val branch = Instruction(Opcode.Branch, IrVoid)
        branch.targets.add(calleeEntry)
        branch.block = block
        block.instructions.add(branch)
        val position = function.blocks.indexOf(block) + 1
        function.blocks.addAll(position, callee.blocks.map { blockMapping.getValue(it) })
        if (result != null) {
            val replacement = mapOf<Value, Value>(call to result)
            for (instruction in function.instructions()) instruction.replaceOperands(replacement)
        }
    }
}
