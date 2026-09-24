package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.passes.Uses

class PrecisionDemotion(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    fun run(function: IrFunction, stage: ShaderStage, profit: Int = PROFIT): Boolean {
        if (stage != ShaderStage.Fragment) return false
        val uses = Uses(function)
        val demoted = LinkedHashSet<Instruction>()
        var changed = true
        while (changed) {
            changed = false
            for (instruction in function.instructions().toList().asReversed()) {
                if (instruction in demoted || !candidate(instruction)) continue
                val users = uses.of(instruction)
                if (users.isEmpty()) continue
                if (users.all { user -> user in demoted || colorStore(user, instruction) }) {
                    demoted.add(instruction)
                    changed = true
                }
            }
        }
        val arithmetic = demoted.count { it.opcode != Opcode.CompositeConstruct && it.opcode != Opcode.CompositeExtract }
        val boundaries = demoted.sumOf { instruction -> instruction.operands.count { it !is IrConstant && it !in demoted } }
        if (arithmetic < MINIMUM_OPERATIONS || arithmetic < boundaries * profit) return false
        rewrite(function, demoted, uses)
        return true
    }

    private fun candidate(instruction: Instruction): Boolean {
        val scalar = float(instruction.type) ?: return false
        if (scalar.bits != 32) return false
        return when (instruction.opcode) {
            Opcode.FAdd, Opcode.FSub, Opcode.FMul, Opcode.FDiv, Opcode.FNeg, Opcode.VectorTimesScalar, Opcode.CompositeConstruct, Opcode.VectorShuffle -> true
            Opcode.CompositeExtract -> float(instruction.operands[0].type) != null
            Opcode.Select -> true
            Opcode.Intrinsic -> instruction.intrinsic in INTRINSICS && isNative(instruction.intrinsic!!, half(instruction))
            else -> false
        }
    }

    private fun half(instruction: Instruction): Instruction =
        Instruction(instruction.opcode, lower(instruction.type), instruction.operands.map { operand -> if (float(operand.type) != null) ConstantScalar.float(HALF, 0.0) else operand })
            .also { it.intrinsic = instruction.intrinsic }

    private fun colorStore(user: Instruction, value: Value): Boolean {
        if (user.opcode != Opcode.Store || user.operands[1] !== value) return false
        val global = user.operands[0] as? GlobalVariable ?: return false
        return global.storage == StorageClass.Output && global.interfaceInfo?.builtin == null
    }

    private fun float(type: IrType): IrFloat? = type as? IrFloat ?: (type as? IrVector)?.element as? IrFloat

    private fun lower(type: IrType): IrType = when (type) {
        is IrVector -> IrVector.of(HALF, type.count)
        else -> HALF
    }

    private fun rewrite(function: IrFunction, demoted: Set<Instruction>, uses: Uses) {
        val builder = IrBuilder(function)
        for (instruction in demoted) {
            val original = instruction.type
            for (i in instruction.operands.indices) {
                val operand = instruction.operands[i]
                if (float(operand.type) == null || operand in demoted) continue
                if (instruction.opcode == Opcode.Select && i == 0) continue
                instruction.operands[i] = when (operand) {
                    is ConstantScalar -> ConstantScalar.float(HALF, ConstantFolding.round(HALF, operand.asDouble))
                    is ConstantComposite -> ConstantComposite(lower(operand.type), operand.elements.map { ConstantScalar.float(HALF, ConstantFolding.round(HALF, (it as ConstantScalar).asDouble)) })
                    else -> {
                        builder.positionBefore(instruction)
                        builder.unary(Opcode.FConvert, lower(operand.type), operand)
                    }
                }
            }
            instruction.type = lower(original)
            for (user in uses.of(instruction)) {
                if (user in demoted) continue
                builder.positionBefore(user)
                val widened = builder.unary(Opcode.FConvert, original, instruction)
                for (i in user.operands.indices) if (user.operands[i] === instruction) user.operands[i] = widened
            }
        }
    }

    private companion object {
        val HALF = IrFloat.F16
        const val MINIMUM_OPERATIONS = 4
        const val PROFIT = 2
        val INTRINSICS = setOf(
            Intrinsic.Fma, Intrinsic.FMin, Intrinsic.FMax, Intrinsic.Saturate, Intrinsic.FClamp, Intrinsic.Mix, Intrinsic.FAbs,
            Intrinsic.Floor, Intrinsic.Ceil, Intrinsic.Fract, Intrinsic.Dot, Intrinsic.Sqrt, Intrinsic.Rsqrt, Intrinsic.Exp2,
            Intrinsic.Log2, Intrinsic.Pow,
        )
    }
}
