package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

object Revectorization {
    private val LANEWISE = setOf(
        Opcode.FAdd, Opcode.FSub, Opcode.FMul, Opcode.FDiv, Opcode.FNeg, Opcode.IAdd, Opcode.ISub, Opcode.IMul,
        Opcode.And, Opcode.Or, Opcode.Xor, Opcode.Shl, Opcode.LShr, Opcode.AShr, Opcode.Select, Opcode.FConvert,
        Opcode.SToF, Opcode.UToF, Opcode.FToS, Opcode.FToU, Opcode.Intrinsic,
    )

    private val INTRINSICS = setOf(
        Intrinsic.FAbs, Intrinsic.FSign, Intrinsic.Floor, Intrinsic.Ceil, Intrinsic.Round, Intrinsic.Rint, Intrinsic.Trunc, Intrinsic.Fract,
        Intrinsic.Sqrt, Intrinsic.Rsqrt, Intrinsic.Sin, Intrinsic.Cos, Intrinsic.Exp2, Intrinsic.Log2, Intrinsic.Pow, Intrinsic.Fma,
        Intrinsic.FMin, Intrinsic.FMax, Intrinsic.FClamp, Intrinsic.Mix, Intrinsic.Step, Intrinsic.Smoothstep, Intrinsic.Saturate,
        Intrinsic.SMin, Intrinsic.SMax, Intrinsic.UMin, Intrinsic.UMax, Intrinsic.SClamp, Intrinsic.UClamp,
    )

    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (instruction in function.instructions().toList()) {
            if (instruction.opcode != Opcode.CompositeConstruct) continue
            val type = instruction.type as? IrVector ?: continue
            if (instruction.operands.size != type.count || instruction.operands.any { it.type !is IrScalar }) continue
            builder.positionBefore(instruction)
            val vector = vectorize(builder, instruction.operands, type, uses) ?: continue
            if (vector !is Instruction || vector.opcode == Opcode.CompositeConstruct) continue
            replacements[instruction] = vector
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun vectorize(builder: IrBuilder, lanes: List<Value>, type: IrVector, uses: Uses): Value? {
        if (lanes.all { it === lanes[0] }) return builder.construct(type, lanes)
        if (lanes.all { it is ConstantScalar }) return ConstantComposite(type, lanes.map { it as IrConstant })
        val source = (lanes[0] as? Instruction)?.takeIf { it.opcode == Opcode.CompositeExtract }?.operands?.get(0)
        if (source != null && source.type == type && lanes.withIndex().all { (i, lane) ->
                lane is Instruction && lane.opcode == Opcode.CompositeExtract && lane.operands[0] === source && lane.literals.contentEquals(intArrayOf(i))
            }
        ) {
            return source
        }
        val first = lanes[0] as? Instruction ?: return null
        if (first.opcode !in LANEWISE || first.opcode == Opcode.Intrinsic && first.intrinsic !in INTRINSICS) return null
        val instructions = lanes.map { it as? Instruction ?: return null }
        if (instructions.any { !isomorphic(first, it) || it.type != type.element }) return null
        if (instructions.any { lane -> uses.of(lane).size != 1 }) return null
        val operands = first.operands.indices.map { k ->
            val column = instructions.map { it.operands[k] }
            val operandType = column[0].type as? IrScalar ?: return null
            if (column.any { it.type != operandType }) return null
            val vectorType = IrVector.of(operandType, type.count)
            vectorize(builder, column, vectorType, uses) ?: builder.construct(vectorType, column)
        }
        return builder.emit(first.opcode, type, operands, first.literals, first.intrinsic)
    }

    private fun isomorphic(a: Instruction, b: Instruction): Boolean =
        a.opcode == b.opcode && a.intrinsic == b.intrinsic && a.literals.contentEquals(b.literals) && a.operands.size == b.operands.size
}
