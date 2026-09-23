package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

object PointerLegalization {
    fun run(function: IrFunction) {
        var rounds = 0
        while (rounds++ < 256) {
            val cfg = ControlFlowGraph(function)
            val candidates = function.instructions().filter { instruction ->
                instruction.opcode == Opcode.PtrDiff ||
                    instruction.type is IrPointer && (instruction.opcode == Opcode.Phi || instruction.opcode == Opcode.Select)
            }.toList()
            var candidate: Instruction? = null
            var replacement: Value? = null
            for (instruction in candidates) {
                replacement = when (instruction.opcode) {
                    Opcode.Phi -> phi(function, cfg, instruction)
                    Opcode.Select -> select(function, instruction)
                    else -> difference(function, instruction)
                }
                if (replacement != null) {
                    candidate = instruction
                    break
                }
            }
            if (candidate == null || replacement == null) return
            candidate.block!!.instructions.remove(candidate)
            for (instruction in function.instructions()) {
                for (i in instruction.operands.indices) if (instruction.operands[i] === candidate) instruction.operands[i] = replacement
            }
        }
    }

    private fun phi(function: IrFunction, cfg: ControlFlowGraph, phi: Instruction): Value? {
        var parts = phi.operands.map { decompose(it, phi) }
        val roots = parts.map { it.root }.filter { it !== phi }.distinct()
        val builder = IrBuilder(function)
        val anchor = phi.block!!.instructions.first { it.opcode != Opcode.Phi }
        val root: Value
        if (roots.size == 1) {
            root = roots[0]
            if (!dominates(cfg, root, phi.block!!)) return null
        } else {
            val chain = commonChain(roots) ?: return null
            if (!dominates(cfg, chain.first, phi.block!!) || chain.second.any { !dominates(cfg, it, phi.block!!) }) return null
            builder.positionBefore(anchor)
            root = builder.accessChain(chain.first, chain.second + ConstantScalar.i32(0))
            parts = parts.map { part ->
                if (part.root === phi) part else PointerDecomposition(root, part.terms + (part.root as Instruction).operands.last())
            }
        }
        builder.position(phi.block!!)
        val offsetPhi = builder.phi(IrInt.I32, emptyList())
        for ((index, part) in parts.withIndex()) {
            val predecessor = phi.targets[index]
            val terminator = predecessor.terminator!!
            builder.positionBefore(terminator)
            val terms = if (part.root === phi) part.terms + offsetPhi else part.terms
            offsetPhi.operands.add(sum(builder, terms))
            offsetPhi.targets.add(predecessor)
        }
        builder.positionBefore(anchor)
        return builder.ptrOffset(root, offsetPhi)
    }

    private fun select(function: IrFunction, select: Instruction): Value? {
        var whenTrue = decompose(select.operands[1], null)
        var whenFalse = decompose(select.operands[2], null)
        val builder = IrBuilder(function)
        builder.positionBefore(select)
        if (whenTrue.root !== whenFalse.root) {
            val chain = commonChain(listOf(whenTrue.root, whenFalse.root)) ?: return null
            val root = builder.accessChain(chain.first, chain.second + ConstantScalar.i32(0))
            whenTrue = PointerDecomposition(root, whenTrue.terms + (whenTrue.root as Instruction).operands.last())
            whenFalse = PointerDecomposition(root, whenFalse.terms + (whenFalse.root as Instruction).operands.last())
        }
        val offset = builder.select(select.operands[0], sum(builder, whenTrue.terms), sum(builder, whenFalse.terms))
        return builder.ptrOffset(whenTrue.root, offset)
    }

    private fun difference(function: IrFunction, difference: Instruction): Value? {
        val left = decompose(difference.operands[0], null)
        val right = decompose(difference.operands[1], null)
        if (left.root !== right.root) return null
        val builder = IrBuilder(function)
        builder.positionBefore(difference)
        val a = sum(builder, left.terms)
        val b = sum(builder, right.terms)
        if (a is ConstantScalar && b is ConstantScalar) return ConstantScalar.i32((a.bits - b.bits).toInt())
        return builder.binary(Opcode.ISub, IrInt.I32, a, b)
    }

    private fun decompose(value: Value, phi: Instruction?): PointerDecomposition {
        var current = value
        val terms = ArrayList<Value>()
        while (current is Instruction && current !== phi && current.opcode == Opcode.PtrOffset) {
            terms.add(current.operands[1])
            current = current.operands[0]
        }
        return PointerDecomposition(current, terms)
    }

    private fun commonChain(roots: List<Value>): Pair<Value, List<Value>>? {
        val chains = roots.map { root -> (root as? Instruction)?.takeIf { it.opcode == Opcode.AccessChain } ?: return null }
        val first = chains[0]
        val size = first.operands.size
        if (size < 2 || chains.any { it.operands.size != size }) return null
        for (i in 0 until size - 1) {
            if (chains.any { !same(it.operands[i], first.operands[i]) }) return null
        }
        var type = (first.operands[0].type as IrPointer).pointee
        for (i in 1 until size - 1) type = IrBuilder.elementType(type, first.operands[i])
        if (type !is IrArray) return null
        return first.operands[0] to first.operands.subList(1, size - 1)
    }

    private fun same(a: Value, b: Value): Boolean =
        a === b || a is ConstantScalar && b is ConstantScalar && a.type == b.type && a.bits == b.bits

    private fun dominates(cfg: ControlFlowGraph, value: Value, block: Block): Boolean {
        if (value !is Instruction) return true
        val definition = value.block ?: return false
        return definition !== block && cfg.dominates(definition, block)
    }

    private fun sum(builder: IrBuilder, terms: List<Value>): Value {
        var total: Value = ConstantScalar.i32(0)
        for (term in terms) {
            val converted = toI32(builder, term)
            total = when {
                total is ConstantScalar && total.bits == 0L -> converted
                total is ConstantScalar && converted is ConstantScalar -> ConstantScalar.i32((total.bits + converted.bits).toInt())
                else -> builder.binary(Opcode.IAdd, IrInt.I32, total, converted)
            }
        }
        return total
    }

    private fun toI32(builder: IrBuilder, value: Value): Value {
        val type = value.type as? IrInt ?: return value
        if (type == IrInt.I32) return value
        if (value is IrConstant && value is ConstantScalar) return ConstantScalar.i32(value.bits.toInt())
        return when {
            type.bits == 32 -> builder.unary(Opcode.Bitcast, IrInt.I32, value)
            type.signed -> builder.unary(Opcode.SConvert, IrInt.I32, value)
            else -> builder.unary(Opcode.UConvert, IrInt.I32, value)
        }
    }
}
