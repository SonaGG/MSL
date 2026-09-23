package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value

class Simplifier(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    private lateinit var builder: IrBuilder

    fun run(function: IrFunction): Boolean {
        builder = IrBuilder(function)
        var changed = false
        var rounds = 0
        while (rounds++ < 8) {
            val replacements = HashMap<Value, Value>()
            for (block in function.blocks) {
                for (instruction in block.instructions.toList()) {
                    for (i in instruction.operands.indices) instruction.operands[i] = IrRewriter.resolve(instruction.operands[i], replacements)
                    val replacement = ConstantFolding.fold(instruction)?.takeIf { Purity.isFoldable(instruction) } ?: simplify(instruction)
                    if (replacement != null && replacement !== instruction) replacements[instruction] = replacement
                }
            }
            if (replacements.isEmpty()) break
            IrRewriter.replace(function, replacements)
            changed = true
        }
        return changed
    }

    private fun at(instruction: Instruction): IrBuilder {
        builder.positionBefore(instruction)
        return builder
    }

    private fun simplify(instruction: Instruction): Value? {
        val ops = instruction.operands
        val type = instruction.type
        return when (instruction.opcode) {
            Opcode.IAdd -> when {
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> ops[1]
                else -> reassociate(instruction)
            }

            Opcode.ISub -> when {
                isValue(ops[1], 0.0) -> ops[0]
                ops[0] === ops[1] -> zero(type)
                ops[1] is IrConstant && ConstantFolding.isFoldable(ops[1]) -> negate(ops[1] as IrConstant)?.let { at(instruction).binary(Opcode.IAdd, type, ops[0], it) }
                else -> null
            }

            Opcode.IMul -> when {
                isValue(ops[1], 0.0) || isValue(ops[0], 0.0) -> zero(type)
                isValue(ops[1], 1.0) -> ops[0]
                isValue(ops[0], 1.0) -> ops[1]
                else -> powerOfTwo(ops[1])?.let { at(instruction).binary(Opcode.Shl, type, ops[0], splat(type, it.toLong())) } ?: reassociate(instruction)
            }

            Opcode.SDiv, Opcode.UDiv -> when {
                isValue(ops[1], 1.0) -> ops[0]
                instruction.opcode == Opcode.UDiv -> powerOfTwo(ops[1])?.let { at(instruction).binary(Opcode.LShr, type, ops[0], splat(type, it.toLong())) }
                else -> null
            }

            Opcode.URem -> when {
                isValue(ops[1], 1.0) -> zero(type)
                else -> powerOfTwo(ops[1])?.let { at(instruction).binary(Opcode.And, type, ops[0], splat(type, (1L shl it) - 1)) }
            }

            Opcode.And -> when {
                type.scalar is IrBool -> logicalAnd(ops[0], ops[1])
                isValue(ops[1], 0.0) || isValue(ops[0], 0.0) -> zero(type)
                isAllOnes(ops[1]) -> ops[0]
                isAllOnes(ops[0]) -> ops[1]
                ops[0] === ops[1] -> ops[0]
                else -> reassociate(instruction)
            }

            Opcode.Or -> when {
                type.scalar is IrBool -> logicalOr(ops[0], ops[1])
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> ops[1]
                ops[0] === ops[1] -> ops[0]
                isAllOnes(ops[1]) -> ops[1]
                isAllOnes(ops[0]) -> ops[0]
                else -> reassociate(instruction)
            }

            Opcode.Xor -> when {
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> ops[1]
                ops[0] === ops[1] -> zero(type)
                else -> reassociate(instruction)
            }

            Opcode.Shl, Opcode.LShr, Opcode.AShr -> if (isValue(ops[1], 0.0)) ops[0] else null
            Opcode.INeg -> operandOf(ops[0], Opcode.INeg)
            Opcode.Not -> operandOf(ops[0], Opcode.Not)
            Opcode.FAdd -> when {
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> ops[1]
                isOp(ops[1], Opcode.FNeg) -> at(instruction).binary(Opcode.FSub, type, ops[0], (ops[1] as Instruction).operands[0])
                isOp(ops[0], Opcode.FNeg) -> at(instruction).binary(Opcode.FSub, type, ops[1], (ops[0] as Instruction).operands[0])
                ops[0] === ops[1] -> at(instruction).binary(Opcode.FMul, type, ops[0], splat(type, 2.0))
                else -> reassociate(instruction)
            }

            Opcode.FSub -> when {
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> at(instruction).unary(Opcode.FNeg, type, ops[1])
                ops[0] === ops[1] -> zero(type)
                isOp(ops[1], Opcode.FNeg) -> at(instruction).binary(Opcode.FAdd, type, ops[0], (ops[1] as Instruction).operands[0])
                ops[1] is IrConstant && ConstantFolding.isFoldable(ops[1]) -> negate(ops[1] as IrConstant)?.let { at(instruction).binary(Opcode.FAdd, type, ops[0], it) }
                else -> null
            }

            Opcode.FMul -> when {
                isValue(ops[1], 1.0) -> ops[0]
                isValue(ops[0], 1.0) -> ops[1]
                isValue(ops[1], 0.0) || isValue(ops[0], 0.0) -> zero(type)
                isValue(ops[1], -1.0) -> at(instruction).unary(Opcode.FNeg, type, ops[0])
                isValue(ops[0], -1.0) -> at(instruction).unary(Opcode.FNeg, type, ops[1])
                isOp(ops[0], Opcode.FNeg) && isOp(ops[1], Opcode.FNeg) ->
                    at(instruction).binary(Opcode.FMul, type, (ops[0] as Instruction).operands[0], (ops[1] as Instruction).operands[0])

                else -> reassociate(instruction)
            }

            Opcode.FDiv -> when {
                isValue(ops[1], 1.0) -> ops[0]
                isValue(ops[0], 0.0) -> zero(type)
                ops[1] is IrConstant && ConstantFolding.isFoldable(ops[1]) && !Constants.isZero(ops[1]) -> reciprocal(ops[1] as IrConstant)?.let { at(instruction).binary(Opcode.FMul, type, ops[0], it) }
                isIntrinsic(ops[1], Intrinsic.Sqrt) && native(Intrinsic.Rsqrt, instruction) -> {
                    val rsqrt = at(instruction).intrinsic(Intrinsic.Rsqrt, type, listOf((ops[1] as Instruction).operands[0]))
                    if (isValue(ops[0], 1.0)) rsqrt else at(instruction).binary(Opcode.FMul, type, ops[0], rsqrt)
                }

                else -> null
            }

            Opcode.FNeg -> operandOf(ops[0], Opcode.FNeg) ?: (ops[0] as? Instruction)?.takeIf { it.opcode == Opcode.FSub }?.let {
                at(instruction).binary(Opcode.FSub, type, it.operands[1], it.operands[0])
            }

            Opcode.LogicalNot -> logicalNot(instruction, ops[0])
            Opcode.LogicalAnd -> logicalAnd(ops[0], ops[1])
            Opcode.LogicalOr -> logicalOr(ops[0], ops[1])
            Opcode.LogicalEqual -> when {
                isValue(ops[1], 1.0) -> ops[0]
                isValue(ops[0], 1.0) -> ops[1]
                ops[0] === ops[1] -> splat(type, 1.0)
                else -> null
            }

            Opcode.LogicalNotEqual -> when {
                isValue(ops[1], 0.0) -> ops[0]
                isValue(ops[0], 0.0) -> ops[1]
                ops[0] === ops[1] -> zero(type)
                else -> null
            }

            Opcode.IEqual, Opcode.SLessEqual, Opcode.SGreaterEqual, Opcode.ULessEqual, Opcode.UGreaterEqual,
            Opcode.FEqual, Opcode.FLessEqual, Opcode.FGreaterEqual,
            -> if (ops[0] === ops[1]) splat(type, 1.0) else null

            Opcode.INotEqual, Opcode.SLess, Opcode.SGreater, Opcode.ULess, Opcode.UGreater,
            Opcode.FNotEqual, Opcode.FLess, Opcode.FGreater,
            -> if (ops[0] === ops[1]) zero(type) else null

            Opcode.Select -> select(instruction)
            Opcode.FConvert, Opcode.SConvert, Opcode.UConvert, Opcode.Bitcast -> when {
                ops[0].type == type -> ops[0]
                instruction.opcode == Opcode.Bitcast && isOp(ops[0], Opcode.Bitcast) && (ops[0] as Instruction).operands[0].type == type -> (ops[0] as Instruction).operands[0]
                else -> null
            }

            Opcode.CompositeExtract -> extract(instruction, ops[0], instruction.literals)
            Opcode.CompositeInsert -> {
                val value = ops[0] as? Instruction
                if (value != null && value.opcode == Opcode.CompositeExtract && value.operands[0] === ops[1] && value.literals.contentEquals(instruction.literals)) ops[1] else null
            }

            Opcode.CompositeConstruct -> construct(instruction)
            Opcode.VectorShuffle -> shuffle(instruction)
            Opcode.VectorExtractDynamic -> (ops[1] as? ConstantScalar)?.let { index ->
                val count = ops[0].type.componentCount
                if (index.bits in 0 until count) at(instruction).extract(ops[0], index.bits.toInt()) else null
            }

            Opcode.VectorInsertDynamic -> (ops[2] as? ConstantScalar)?.let { index ->
                val count = ops[0].type.componentCount
                if (index.bits in 0 until count) at(instruction).insert(ops[0], ops[1], index.bits.toInt()) else null
            }

            Opcode.MatrixTimesScalar, Opcode.VectorTimesScalar -> if (isValue(ops[1], 1.0)) ops[0] else null
            Opcode.Load -> constantLoad(ops[0])
            Opcode.Intrinsic -> intrinsic(instruction)
            else -> null
        }
    }

    private fun native(intrinsic: Intrinsic, context: Instruction): Boolean = isNative(intrinsic, context)

    private fun isOp(value: Value, opcode: Opcode): Boolean = value is Instruction && value.opcode == opcode

    private fun isIntrinsic(value: Value, intrinsic: Intrinsic): Boolean = value is Instruction && value.opcode == Opcode.Intrinsic && value.intrinsic == intrinsic

    private fun operandOf(value: Value, opcode: Opcode): Value? = if (isOp(value, opcode)) (value as Instruction).operands[0] else null

    private fun scalarsOf(value: Value): List<ConstantScalar>? = when (value) {
        is ConstantScalar -> listOf(value)
        is ConstantNull -> if (value.type is IrScalar || value.type is IrVector) List(value.type.componentCount) { Constants.zero(value.type.scalar) as ConstantScalar } else null
        is ConstantComposite -> value.elements.map { it as? ConstantScalar ?: return null }.takeIf { value.type is IrVector }
        else -> null
    }

    private fun isValue(value: Value, number: Double): Boolean {
        val scalars = scalarsOf(value) ?: return false
        return scalars.all { scalar ->
            when (scalar.type) {
                is IrFloat -> scalar.asDouble == number
                is IrBool -> scalar.asBoolean == (number != 0.0)
                is IrInt -> scalar.bits == number.toLong()
            }
        }
    }

    private fun isAllOnes(value: Value): Boolean {
        val scalars = scalarsOf(value) ?: return false
        return scalars.all { scalar -> (scalar.type as? IrInt)?.let { ConstantScalar.int(it, -1).bits == scalar.bits } == true }
    }

    private fun powerOfTwo(value: Value): Int? {
        val scalars = scalarsOf(value) ?: return null
        val first = scalars.firstOrNull() ?: return null
        if (first.type !is IrInt || scalars.any { it.bits != first.bits }) return null
        val bits = first.bits
        if (bits <= 1 || bits and (bits - 1) != 0L) return null
        return bits.countTrailingZeroBits()
    }

    private fun zero(type: IrType): IrConstant = if (type is IrVector) Constants.splat(type, Constants.zero(type.element) as ConstantScalar) else Constants.zero(type)

    private fun splat(type: IrType, value: Double): IrConstant = Constants.splat(type, Constants.scalar(type.scalar, value))

    private fun splat(type: IrType, value: Long): IrConstant = Constants.splat(type, Constants.integer(type.scalar, value))

    private fun negate(value: IrConstant): IrConstant? {
        val scalars = scalarsOf(value) ?: return null
        val negated = scalars.map { scalar ->
            when (val type = scalar.type) {
                is IrFloat -> ConstantScalar.float(type, -scalar.asDouble)
                is IrInt -> ConstantScalar.int(type, -scalar.bits)
                is IrBool -> return null
            }
        }
        return if (value.type is IrVector) ConstantComposite(value.type, negated) else negated[0]
    }

    private fun reciprocal(value: IrConstant): IrConstant? {
        val scalars = scalarsOf(value) ?: return null
        val inverted = scalars.map { scalar ->
            val type = scalar.type as? IrFloat ?: return null
            ConstantScalar.float(type, ConstantFolding.round(type, 1.0 / scalar.asDouble))
        }
        return if (value.type is IrVector) ConstantComposite(value.type, inverted) else inverted[0]
    }

    private fun reassociate(instruction: Instruction): Value? {
        val ops = instruction.operands
        val constantIndex = if (ops[1] is IrConstant && ConstantFolding.isFoldable(ops[1])) 1 else if (ops[0] is IrConstant && ConstantFolding.isFoldable(ops[0])) 0 else return null
        val inner = ops[1 - constantIndex] as? Instruction ?: return null
        if (inner.opcode != instruction.opcode || inner.type != instruction.type) return null
        val innerConstant = inner.operands.indexOfFirst { it is IrConstant && ConstantFolding.isFoldable(it) }
        if (innerConstant < 0) return null
        val combinedInstruction = Instruction(instruction.opcode, instruction.type, listOf(ops[constantIndex], inner.operands[innerConstant]))
        val combined = ConstantFolding.fold(combinedInstruction) ?: return null
        return at(instruction).binary(instruction.opcode, instruction.type, inner.operands[1 - innerConstant], combined)
    }

    private fun logicalNot(instruction: Instruction, operand: Value): Value? {
        val inner = operand as? Instruction ?: return null
        val inverted = INVERSE[inner.opcode]
        return when {
            inner.opcode == Opcode.LogicalNot -> inner.operands[0]
            inverted != null -> at(instruction).binary(inverted, instruction.type, inner.operands[0], inner.operands[1])
            else -> null
        }
    }

    private fun logicalAnd(a: Value, b: Value): Value? = when {
        isValue(a, 1.0) -> b
        isValue(b, 1.0) -> a
        isValue(a, 0.0) -> a
        isValue(b, 0.0) -> b
        a === b -> a
        else -> null
    }

    private fun logicalOr(a: Value, b: Value): Value? = when {
        isValue(a, 0.0) -> b
        isValue(b, 0.0) -> a
        isValue(a, 1.0) -> a
        isValue(b, 1.0) -> b
        a === b -> a
        else -> null
    }

    private fun select(instruction: Instruction): Value? {
        val (condition, whenTrue, whenFalse) = instruction.operands
        return when {
            whenTrue === whenFalse -> whenTrue
            whenTrue == whenFalse && whenTrue is IrConstant -> whenTrue
            instruction.type is IrBool && isValue(whenTrue, 1.0) && isValue(whenFalse, 0.0) && condition.type is IrBool -> condition
            instruction.type is IrBool && isValue(whenTrue, 0.0) && isValue(whenFalse, 1.0) && condition.type is IrBool ->
                at(instruction).unary(Opcode.LogicalNot, IrBool, condition)

            isOp(condition, Opcode.LogicalNot) -> at(instruction).select((condition as Instruction).operands[0], whenFalse, whenTrue)
            else -> null
        }
    }

    private fun extract(instruction: Instruction, composite: Value, path: IntArray): Value? {
        if (path.isEmpty()) return composite
        val source = composite as? Instruction
        if (composite is GlobalVariable || source == null) return null
        return when (source.opcode) {
            Opcode.CompositeExtract -> at(instruction).extract(source.operands[0], *(source.literals + path))
            Opcode.CompositeInsert -> {
                val inserted = source.literals
                val common = minOf(inserted.size, path.size)
                val prefixMatches = (0 until common).all { inserted[it] == path[it] }
                when {
                    !prefixMatches -> at(instruction).extract(source.operands[1], *path)
                    inserted.size == path.size -> source.operands[0]
                    inserted.size < path.size -> at(instruction).extract(source.operands[0], *path.copyOfRange(inserted.size, path.size))
                    else -> null
                }
            }

            Opcode.CompositeConstruct -> {
                val type = source.type
                val rest = path.copyOfRange(1, path.size)
                if (type is IrVector) {
                    var lane = path[0]
                    for (part in source.operands) {
                        val width = part.type.componentCount
                        if (lane < width) {
                            return if (part.type is IrVector) at(instruction).extract(part, lane) else part
                        }
                        lane -= width
                    }
                    null
                } else {
                    val part = source.operands.getOrNull(path[0]) ?: return null
                    if (rest.isEmpty()) part else at(instruction).extract(part, *rest)
                }
            }

            Opcode.VectorShuffle -> {
                val lane = source.literals.getOrNull(path[0]) ?: return null
                val first = source.operands[0]
                val width = first.type.componentCount
                if (lane < width) at(instruction).extract(first, lane) else at(instruction).extract(source.operands[1], lane - width)
            }

            else -> null
        }
    }

    private fun laneSource(value: Value, lane: Int): Pair<Value, Int> {
        val source = value as? Instruction ?: return value to lane
        return when (source.opcode) {
            Opcode.VectorShuffle -> {
                val index = source.literals[lane]
                val width = source.operands[0].type.componentCount
                if (index < width) laneSource(source.operands[0], index) else laneSource(source.operands[1], index - width)
            }

            Opcode.CompositeConstruct -> {
                var remaining = lane
                for (part in source.operands) {
                    val width = part.type.componentCount
                    if (remaining < width) return if (part.type is IrVector) laneSource(part, remaining) else value to lane
                    remaining -= width
                }
                value to lane
            }

            else -> value to lane
        }
    }

    private fun construct(instruction: Instruction): Value? {
        val type = instruction.type as? IrVector ?: return null
        val lanes = ArrayList<Pair<Value, Int>>()
        for (part in instruction.operands) {
            if (part.type is IrVector) {
                for (i in 0 until part.type.componentCount) lanes.add(laneSource(part, i))
            } else {
                val extract = part as? Instruction
                if (extract == null || extract.opcode != Opcode.CompositeExtract || extract.literals.size != 1 || extract.operands[0].type !is IrVector) return null
                lanes.add(laneSource(extract.operands[0], extract.literals[0]))
            }
        }
        return fromLanes(instruction, type, lanes)
    }

    private fun shuffle(instruction: Instruction): Value? {
        val type = instruction.type as IrVector
        val lanes = instruction.literals.map { index ->
            val width = instruction.operands[0].type.componentCount
            if (index < width) laneSource(instruction.operands[0], index) else laneSource(instruction.operands[1], index - width)
        }
        return fromLanes(instruction, type, lanes)
    }

    private fun fromLanes(instruction: Instruction, type: IrVector, lanes: List<Pair<Value, Int>>): Value? {
        val sources = lanes.map { it.first }.distinct()
        if (sources.any { it.type !is IrVector || it.type.scalar != type.element }) return null
        if (sources.size == 1 && lanes.map { it.second } == List(type.count) { it } && sources[0].type == type) return sources[0]
        if (sources.size > 2) return null
        val first = sources[0]
        val second = sources.getOrElse(1) { first }
        val width = first.type.componentCount
        val literals = lanes.map { (source, lane) -> if (source === first) lane else width + lane }.toIntArray()
        if (instruction.opcode == Opcode.VectorShuffle && instruction.operands[0] === first && instruction.operands[1] === second && instruction.literals.contentEquals(literals)) return null
        return at(instruction).shuffle(first, second, literals)
    }

    private fun constantLoad(pointer: Value): Value? {
        val global = Purity.rootGlobal(pointer) ?: return null
        if (!global.isConstant) return null
        val initializer = global.initializer ?: return null
        if (pointer === global) return initializer
        val chain = pointer as? Instruction ?: return null
        if (chain.opcode != Opcode.AccessChain || chain.operands[0] !== global) return null
        var current: IrConstant = initializer
        for (index in chain.operands.drop(1)) {
            val position = (index as? ConstantScalar)?.bits?.toInt() ?: return null
            current = when (current) {
                is ConstantComposite -> current.elements.getOrNull(position) ?: return null
                is ConstantNull -> return null
                else -> return null
            }
        }
        return current
    }

    private fun intrinsic(instruction: Instruction): Value? {
        val ops = instruction.operands
        val type = instruction.type
        return when (instruction.intrinsic) {
            Intrinsic.Pow, Intrinsic.Powr -> {
                val exponent = scalarsOf(ops[1])?.map { it.asDouble }?.distinct()?.singleOrNull() ?: return null
                val builder = at(instruction)
                when (exponent) {
                    0.0 -> splat(type, 1.0)
                    1.0 -> ops[0]
                    2.0 -> builder.binary(Opcode.FMul, type, ops[0], ops[0])
                    3.0 -> builder.binary(Opcode.FMul, type, builder.binary(Opcode.FMul, type, ops[0], ops[0]), ops[0])
                    4.0 -> builder.binary(Opcode.FMul, type, ops[0], ops[0]).let { square -> builder.binary(Opcode.FMul, type, square, square) }
                    -1.0 -> builder.binary(Opcode.FDiv, type, splat(type, 1.0), ops[0])
                    0.5 -> if (native(Intrinsic.Sqrt, instruction)) builder.intrinsic(Intrinsic.Sqrt, type, listOf(ops[0])) else null
                    -0.5 -> if (native(Intrinsic.Rsqrt, instruction)) builder.intrinsic(Intrinsic.Rsqrt, type, listOf(ops[0])) else null
                    else -> null
                }
            }

            Intrinsic.FMin, Intrinsic.FMax, Intrinsic.SMin, Intrinsic.SMax, Intrinsic.UMin, Intrinsic.UMax -> if (ops[0] === ops[1]) ops[0] else null
            Intrinsic.FAbs -> when {
                isIntrinsic(ops[0], Intrinsic.FAbs) -> ops[0]
                isOp(ops[0], Opcode.FNeg) -> at(instruction).intrinsic(Intrinsic.FAbs, type, listOf((ops[0] as Instruction).operands[0]))
                else -> null
            }

            Intrinsic.Floor, Intrinsic.Ceil, Intrinsic.Trunc, Intrinsic.Rint, Intrinsic.Saturate -> if (isIntrinsic(ops[0], instruction.intrinsic!!)) ops[0] else null
            Intrinsic.FClamp -> if (isValue(ops[1], 0.0) && isValue(ops[2], 1.0) && native(Intrinsic.Saturate, instruction)) {
                at(instruction).intrinsic(Intrinsic.Saturate, type, listOf(ops[0]))
            } else {
                null
            }

            Intrinsic.Mix -> when {
                isValue(ops[2], 0.0) -> ops[0]
                isValue(ops[2], 1.0) -> ops[1]
                ops[0] === ops[1] -> ops[0]
                else -> null
            }

            Intrinsic.Dot -> if (Constants.isZero(ops[0]) || Constants.isZero(ops[1])) zero(type) else null
            else -> null
        }
    }

    private companion object {
        val INVERSE = mapOf(
            Opcode.IEqual to Opcode.INotEqual, Opcode.INotEqual to Opcode.IEqual,
            Opcode.SLess to Opcode.SGreaterEqual, Opcode.SGreaterEqual to Opcode.SLess,
            Opcode.SGreater to Opcode.SLessEqual, Opcode.SLessEqual to Opcode.SGreater,
            Opcode.ULess to Opcode.UGreaterEqual, Opcode.UGreaterEqual to Opcode.ULess,
            Opcode.UGreater to Opcode.ULessEqual, Opcode.ULessEqual to Opcode.UGreater,
            Opcode.FEqual to Opcode.FNotEqual, Opcode.FNotEqual to Opcode.FEqual,
            Opcode.FLess to Opcode.FGreaterEqual, Opcode.FGreaterEqual to Opcode.FLess,
            Opcode.FGreater to Opcode.FLessEqual, Opcode.FLessEqual to Opcode.FGreater,
            Opcode.LogicalEqual to Opcode.LogicalNotEqual, Opcode.LogicalNotEqual to Opcode.LogicalEqual,
        )
    }
}
