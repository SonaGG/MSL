package gg.sona.msl.opt

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

class FmaFormation(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        val fused = HashSet<Instruction>()
        val exact = exactValues(function)
        for (block in function.blocks) {
            for (instruction in block.instructions.toList()) {
                if (instruction.opcode != Opcode.FAdd && instruction.opcode != Opcode.FSub) continue
                val scalar = instruction.type.scalar as? IrFloat ?: continue
                if (scalar.bits == 64 || instruction in exact || !isNative(Intrinsic.Fma, instruction)) continue
                for (i in instruction.operands.indices) instruction.operands[i] = IrRewriter.resolve(instruction.operands[i], replacements)
                val (left, right) = instruction.operands
                val index = when {
                    product(left, instruction, uses, fused) -> 0
                    product(right, instruction, uses, fused) -> 1
                    else -> continue
                }
                val product = instruction.operands[index] as Instruction
                val addend = instruction.operands[1 - index]
                builder.positionBefore(instruction)
                val subtract = instruction.opcode == Opcode.FSub
                var a = product.operands[0]
                var c = addend
                if (subtract && index == 1) a = negate(builder, a)
                if (subtract && index == 0) c = negate(builder, c)
                fused.add(product)
                replacements[instruction] = builder.intrinsic(Intrinsic.Fma, instruction.type, listOf(a, product.operands[1], c))
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun exactValues(function: IrFunction): Set<Instruction> {
        val exact = HashSet<Instruction>()
        val pending = ArrayDeque<Value>()
        for (instruction in function.instructions()) if (instruction.opcode == Opcode.Bitcast) pending.add(instruction.operands[0])
        while (pending.isNotEmpty()) {
            val value = pending.removeLast() as? Instruction ?: continue
            if (value.opcode !in ARITHMETIC || !exact.add(value)) continue
            pending.addAll(value.operands)
        }
        return exact
    }

    private fun negate(builder: IrBuilder, value: Value): Value {
        if (value is Instruction && value.opcode == Opcode.FNeg) return value.operands[0]
        if (value is IrConstant) ConstantFolding.fold(Instruction(Opcode.FNeg, value.type, listOf(value)))?.let { return it }
        return builder.unary(Opcode.FNeg, value.type, value)
    }

    private fun product(value: Value, user: Instruction, uses: Uses, fused: Set<Instruction>): Boolean {
        val product = value as? Instruction ?: return false
        if (product.opcode != Opcode.FMul || product.type != user.type || product in fused) return false
        return uses.of(product).all { it === user }
    }

    private companion object {
        val ARITHMETIC = setOf(
            Opcode.FAdd, Opcode.FSub, Opcode.FMul, Opcode.FNeg, Opcode.CompositeConstruct, Opcode.CompositeExtract,
            Opcode.VectorShuffle, Opcode.FConvert,
        )
    }
}
