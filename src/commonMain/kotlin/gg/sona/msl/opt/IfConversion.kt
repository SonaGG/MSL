package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uniformity

class IfConversion(private val budget: Int, private val uniformBudget: Int) {
    fun run(function: IrFunction): Boolean {
        var changed = false
        while (true) {
            val blocks = StructuredBlocks(function)
            val uniformity = Uniformity(function)
            val touched = HashSet<Block>()
            val replacements = HashMap<Value, Value>()
            val removed = HashSet<Block>()
            for (header in function.blocks.toList()) {
                if (!convertible(header, blocks, uniformity, touched)) continue
                convert(header, uniformity, touched, removed, replacements)
            }
            if (touched.isEmpty()) return changed
            function.blocks.removeAll(removed)
            IrRewriter.replace(function, replacements)
            changed = true
        }
    }

    private fun arm(header: Block, target: Block, merge: Block, blocks: StructuredBlocks, limit: Int): Boolean {
        if (target === merge) return true
        if (target.construct != ConstructKind.None || target in blocks.structural) return false
        if (blocks.predecessorsOf(target) != listOf(header)) return false
        val terminator = target.terminator ?: return false
        if (terminator.opcode != Opcode.Branch || terminator.targets[0] !== merge) return false
        if (target.phis.isNotEmpty()) return false
        var cost = 0
        for (instruction in target.instructions) {
            if (instruction === terminator) continue
            if (!speculatable(instruction)) return false
            cost += Purity.cost(instruction)
        }
        return cost <= limit
    }

    private fun speculatable(instruction: Instruction): Boolean {
        if (Purity.isSpeculatable(instruction)) return true
        if (instruction.opcode != Opcode.Load) return false
        val storage = Purity.rootStorage(instruction.operands[0])
        if (storage != StorageClass.Uniform && storage != StorageClass.PushConstant && storage != StorageClass.Input) return false
        var pointer: Value = instruction.operands[0]
        while (pointer is Instruction) {
            if (pointer.opcode != Opcode.AccessChain) return false
            if (pointer.operands.drop(1).any { it !is gg.sona.msl.ir.ConstantScalar }) return false
            pointer = pointer.operands[0]
        }
        return true
    }

    private fun convertible(header: Block, blocks: StructuredBlocks, uniformity: Uniformity, touched: Set<Block>): Boolean {
        if (header in touched || header.construct != ConstructKind.Selection) return false
        val terminator = header.terminator ?: return false
        if (terminator.opcode != Opcode.CondBranch || terminator.operands[0].type != IrBool) return false
        val merge = header.merge ?: return false
        val whenTrue = terminator.targets[0]
        val whenFalse = terminator.targets[1]
        if (whenTrue === whenFalse || merge in touched || whenTrue in touched || whenFalse in touched) return false
        val limit = if (uniformity.isUniform(terminator.operands[0])) uniformBudget else budget
        if (!arm(header, whenTrue, merge, blocks, limit) || !arm(header, whenFalse, merge, blocks, limit)) return false
        val expected = setOf(if (whenTrue === merge) header else whenTrue, if (whenFalse === merge) header else whenFalse)
        if (blocks.predecessorsOf(merge).toSet() != expected) return false
        return merge.phis.all { phi -> phi.type !is IrStruct && phi.type !is IrArray && phi.type !is IrMatrix && phi.type != IrVoid && phi.operands.size == 2 }
    }

    private fun convert(
        header: Block,
        uniformity: Uniformity,
        touched: MutableSet<Block>,
        removed: MutableSet<Block>,
        replacements: MutableMap<Value, Value>,
    ) {
        val terminator = header.terminator!!
        val condition = terminator.operands[0]
        val merge = header.merge!!
        val whenTrue = terminator.targets[0]
        val whenFalse = terminator.targets[1]
        for (arm in listOf(whenTrue, whenFalse)) {
            if (arm === merge) continue
            for (instruction in arm.instructions.toList()) {
                if (instruction.isTerminator) continue
                instruction.block = header
                header.instructions.add(header.instructions.size - 1, instruction)
            }
        }
        val trueEdge = if (whenTrue === merge) header else whenTrue
        val falseEdge = if (whenFalse === merge) header else whenFalse
        touched += listOf(header, merge, whenTrue, whenFalse)
        for (phi in merge.phis) {
            val trueValue = phi.operands[phi.targets.indexOf(trueEdge)]
            val falseValue = phi.operands[phi.targets.indexOf(falseEdge)]
            val operands = listOf(condition, trueValue, falseValue).map { IrRewriter.resolve(it, replacements) }
            val select = Instruction(Opcode.Select, phi.type, operands)
            uniformity.inherit(phi, select)
            select.block = header
            header.instructions.add(header.instructions.size - 1, select)
            replacements[phi] = select
        }
        terminator.opcode = Opcode.Branch
        terminator.operands.clear()
        terminator.targets.clear()
        terminator.targets.add(merge)
        header.clearConstruct()
        if (whenTrue !== merge) removed.add(whenTrue)
        if (whenFalse !== merge) removed.add(whenFalse)
    }
}
