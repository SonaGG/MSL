package gg.sona.msl.opt

import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

class RangeFolding(private val entry: EntryPoint?) {
    private val ranges = HashMap<Value, IntegerRange>()

    fun run(function: IrFunction): Boolean {
        analyze(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (instruction in function.instructions().toList()) {
            builder.positionBefore(instruction)
            val replacement = fold(builder, instruction) ?: continue
            replacements[instruction] = replacement
        }
        ranges.clear()
        top.clear()
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun integer(value: Value): Boolean = (value.type as? IrInt)?.bits == 32

    private fun range(value: Value): IntegerRange? = when (value) {
        is ConstantScalar -> if (integer(value)) IntegerRange.of(value.bits, value.bits) else null
        else -> ranges[value]
    }

    private fun analyze(function: IrFunction) {
        val visits = HashMap<Instruction, Int>()
        top.clear()
        var changed = true
        while (changed) {
            changed = false
            for (instruction in function.instructions()) {
                if (!integer(instruction) || instruction in top) continue
                val computed = transfer(instruction)
                val previous = ranges[instruction]
                val next = if (computed == null) null else previous?.union(computed) ?: computed
                if (next == previous && next != null) continue
                val count = (visits[instruction] ?: 0) + 1
                visits[instruction] = count
                if (next == null || count > WIDENING) {
                    ranges.remove(instruction)
                    top.add(instruction)
                } else {
                    ranges[instruction] = next
                }
                changed = true
            }
        }
    }

    private val top = HashSet<Value>()

    private fun phi(instruction: Instruction): IntegerRange? {
        var result: IntegerRange? = null
        for (operand in instruction.operands) {
            val known = range(operand)
            if (known == null) {
                if (operand is Instruction && operand !in top && integer(operand)) continue
                return null
            }
            result = result?.union(known) ?: known
        }
        return result
    }

    private fun transfer(instruction: Instruction): IntegerRange? {
        val ops = instruction.operands
        val a = ops.getOrNull(0)?.let(::range)
        val b = ops.getOrNull(1)?.let(::range)
        val constant = (ops.getOrNull(1) as? ConstantScalar)?.bits?.and(0xFFFFFFFFL)
        return when (instruction.opcode) {
            Opcode.IAdd -> if (a != null && b != null) IntegerRange.of(a.low + b.low, a.high + b.high) else null
            Opcode.ISub -> if (a != null && b != null) IntegerRange.of(a.low - b.high, a.high - b.low) else null
            Opcode.IMul -> if (a != null && b != null) IntegerRange.of(a.low * b.low, a.high * b.high) else null
            Opcode.And -> when {
                a != null && b != null -> IntegerRange.of(0, minOf(a.high, b.high))
                a != null -> IntegerRange.of(0, a.high)
                b != null -> IntegerRange.of(0, b.high)
                else -> null
            }

            Opcode.Or, Opcode.Xor -> if (a != null && b != null) IntegerRange.of(0, mask(maxOf(a.high, b.high))) else null
            Opcode.Shl -> if (a != null && constant != null && constant < 31) IntegerRange.of(a.low shl constant.toInt(), a.high shl constant.toInt()) else null
            Opcode.LShr -> when {
                constant == null || constant >= 32 -> null
                a != null -> IntegerRange.of(a.low shr constant.toInt(), a.high shr constant.toInt())
                constant >= 1 -> IntegerRange.of(0, 0xFFFFFFFFL shr constant.toInt())
                else -> null
            }

            Opcode.AShr -> if (a != null && constant != null && constant < 32) IntegerRange.of(a.low shr constant.toInt(), a.high shr constant.toInt()) else null
            Opcode.URem -> when {
                constant == null || constant == 0L -> null
                a != null && a.high < constant -> a
                else -> IntegerRange.of(0, minOf(constant - 1, a?.high ?: IntegerRange.LIMIT))
            }

            Opcode.SRem -> if (a != null && constant != null && constant in 1..IntegerRange.LIMIT) IntegerRange.of(0, minOf(constant - 1, a.high)) else null
            Opcode.UDiv -> when {
                constant == null || constant == 0L -> null
                a != null -> IntegerRange.of(a.low / constant, a.high / constant)
                constant >= 2 -> IntegerRange.of(0, 0xFFFFFFFFL / constant)
                else -> null
            }

            Opcode.SDiv -> if (a != null && constant != null && constant in 1..IntegerRange.LIMIT) IntegerRange.of(a.low / constant, a.high / constant) else null
            Opcode.Select -> {
                val t = range(ops[1])
                val f = range(ops[2])
                if (t != null && f != null) t.union(f) else null
            }

            Opcode.Phi -> phi(instruction)
            Opcode.Bitcast, Opcode.UConvert, Opcode.SConvert -> a
            Opcode.CompositeExtract -> builtin(instruction)
            Opcode.Load -> builtin(instruction)
            Opcode.Intrinsic -> when (instruction.intrinsic) {
                Intrinsic.UMin, Intrinsic.SMin -> when {
                    a != null && b != null -> IntegerRange.of(minOf(a.low, b.low), minOf(a.high, b.high))
                    instruction.intrinsic == Intrinsic.UMin && (a ?: b) != null -> IntegerRange.of(0, (a ?: b)!!.high)
                    else -> null
                }

                Intrinsic.UMax, Intrinsic.SMax -> if (a != null && b != null) IntegerRange.of(maxOf(a.low, b.low), maxOf(a.high, b.high)) else null
                Intrinsic.Popcount -> IntegerRange.of(0, 32)
                else -> null
            }

            else -> null
        }
    }

    private fun mask(value: Long): Long {
        var result = 0L
        while (result < value) result = result * 2 + 1
        return result
    }

    private fun builtin(instruction: Instruction): IntegerRange? {
        val (load, lane) = when (instruction.opcode) {
            Opcode.CompositeExtract -> (instruction.operands[0] as? Instruction ?: return null) to instruction.literals.singleOrNull()
            else -> instruction to null
        }
        if (load.opcode != Opcode.Load) return null
        val pointer = load.operands[0]
        val (global, index) = when {
            pointer is GlobalVariable -> pointer to lane
            pointer is Instruction && pointer.opcode == Opcode.AccessChain && pointer.operands[0] is GlobalVariable ->
                pointer.operands[0] as GlobalVariable to (pointer.operands.getOrNull(1) as? ConstantScalar)?.bits?.toInt()
            else -> return null
        }
        val size = entry?.workgroupSize?.takeIf { !it.specializable }
        return when (global.interfaceInfo?.builtin) {
            BuiltinVariable.SubgroupLocalInvocationId -> IntegerRange.of(0, MAX_SUBGROUP - 1)
            BuiltinVariable.SubgroupSize -> IntegerRange.of(1, MAX_SUBGROUP)
            BuiltinVariable.LocalInvocationIndex -> size?.let { IntegerRange.of(0, it.x.toLong() * it.y * it.z - 1) }
            BuiltinVariable.LocalInvocationId -> if (size != null && index != null) {
                IntegerRange.of(0, listOf(size.x, size.y, size.z)[index].toLong() - 1)
            } else {
                null
            }

            else -> null
        }
    }

    private fun fold(builder: IrBuilder, instruction: Instruction): Value? {
        val ops = instruction.operands
        return when (instruction.opcode) {
            Opcode.ULess, Opcode.SLess, Opcode.ULessEqual, Opcode.SLessEqual, Opcode.UGreater, Opcode.SGreater,
            Opcode.UGreaterEqual, Opcode.SGreaterEqual, Opcode.IEqual, Opcode.INotEqual,
            -> compare(instruction.opcode, range(ops[0]) ?: return null, range(ops[1]) ?: return null)?.let(ConstantScalar::bool)

            Opcode.And -> {
                val a = range(ops[0])
                val c = (ops[1] as? ConstantScalar)?.bits?.and(0xFFFFFFFFL)
                if (a != null && c != null && c == mask(c) && a.high <= c) ops[0] else null
            }

            Opcode.URem -> {
                val a = range(ops[0])
                val c = (ops[1] as? ConstantScalar)?.bits?.and(0xFFFFFFFFL)
                if (a != null && c != null && a.high < c) ops[0] else null
            }

            Opcode.UDiv -> {
                val a = range(ops[0])
                val c = (ops[1] as? ConstantScalar)?.bits?.and(0xFFFFFFFFL)
                if (a != null && c != null && a.high < c) ConstantScalar.int(instruction.type as IrInt, 0) else null
            }

            Opcode.SDiv, Opcode.SRem -> if (range(ops[0]) != null && range(ops[1]) != null) {
                builder.binary(if (instruction.opcode == Opcode.SDiv) Opcode.UDiv else Opcode.URem, instruction.type, ops[0], ops[1])
            } else {
                null
            }

            Opcode.Intrinsic -> {
                val a = range(ops.getOrNull(0) ?: return null) ?: return null
                val b = range(ops.getOrNull(1) ?: return null) ?: return null
                when (instruction.intrinsic) {
                    Intrinsic.UMin, Intrinsic.SMin -> if (a.high <= b.low) ops[0] else if (b.high <= a.low) ops[1] else null
                    Intrinsic.UMax, Intrinsic.SMax -> if (a.low >= b.high) ops[0] else if (b.low >= a.high) ops[1] else null
                    else -> null
                }
            }

            else -> null
        }?.takeIf { it.type == instruction.type || instruction.type == IrBool }
    }

    private fun compare(opcode: Opcode, a: IntegerRange, b: IntegerRange): Boolean? = when (opcode) {
        Opcode.ULess, Opcode.SLess -> if (a.high < b.low) true else if (a.low >= b.high) false else null
        Opcode.ULessEqual, Opcode.SLessEqual -> if (a.high <= b.low) true else if (a.low > b.high) false else null
        Opcode.UGreater, Opcode.SGreater -> if (a.low > b.high) true else if (a.high <= b.low) false else null
        Opcode.UGreaterEqual, Opcode.SGreaterEqual -> if (a.low >= b.high) true else if (a.high < b.low) false else null
        Opcode.IEqual -> if (a.high < b.low || b.high < a.low) false else if (a.low == a.high && a == b) true else null
        Opcode.INotEqual -> if (a.high < b.low || b.high < a.low) true else if (a.low == a.high && a == b) false else null
        else -> null
    }

    private companion object {
        const val WIDENING = 8
        const val MAX_SUBGROUP = 128L
    }
}
