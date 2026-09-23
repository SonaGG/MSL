package gg.sona.msl.dxil

import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.llvm.LlvmBuilder
import gg.sona.msl.llvm.LlvmConstantFloat
import gg.sona.msl.llvm.LlvmConstantInt
import gg.sona.msl.llvm.LlvmFloatType
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmType
import gg.sona.msl.llvm.LlvmValue

class DxilArithmetic(private val emitter: DxilEmitter) {
    private val builder = emitter.builder

    fun emit(instruction: Instruction): List<LlvmValue> {
        val operands = instruction.operands
        val type = instruction.type
        val scalar = if (type is IrScalar || type is IrVector) type.scalar else null
        return when (val opcode = instruction.opcode) {
            Opcode.IAdd -> binary(instruction, LlvmBuilder.BINOP_ADD, normalize = true)
            Opcode.ISub -> binary(instruction, LlvmBuilder.BINOP_SUB, normalize = true)
            Opcode.IMul -> binary(instruction, LlvmBuilder.BINOP_MUL, normalize = true)
            Opcode.SDiv, Opcode.FDiv -> binary(instruction, LlvmBuilder.BINOP_SDIV, normalize = true)
            Opcode.UDiv -> binary(instruction, LlvmBuilder.BINOP_UDIV, normalize = false)
            Opcode.SRem, Opcode.FRem -> binary(instruction, LlvmBuilder.BINOP_SREM, normalize = false)
            Opcode.URem -> binary(instruction, LlvmBuilder.BINOP_UREM, normalize = false)
            Opcode.FAdd -> binary(instruction, LlvmBuilder.BINOP_ADD, normalize = false)
            Opcode.FSub -> binary(instruction, LlvmBuilder.BINOP_SUB, normalize = false)
            Opcode.FMul -> binary(instruction, LlvmBuilder.BINOP_MUL, normalize = false)
            Opcode.And -> binary(instruction, LlvmBuilder.BINOP_AND, normalize = false)
            Opcode.Or -> binary(instruction, LlvmBuilder.BINOP_OR, normalize = false)
            Opcode.Xor -> binary(instruction, LlvmBuilder.BINOP_XOR, normalize = true)
            Opcode.Shl -> binary(instruction, LlvmBuilder.BINOP_SHL, normalize = true)
            Opcode.LShr -> binary(instruction, LlvmBuilder.BINOP_LSHR, normalize = false)
            Opcode.AShr -> binary(instruction, LlvmBuilder.BINOP_ASHR, normalize = false)
            Opcode.LogicalAnd -> binary(instruction, LlvmBuilder.BINOP_AND, normalize = false)
            Opcode.LogicalOr -> binary(instruction, LlvmBuilder.BINOP_OR, normalize = false)
            Opcode.LogicalNotEqual -> binary(instruction, LlvmBuilder.BINOP_XOR, normalize = false)
            Opcode.LogicalEqual -> componentwise2(instruction) { a, b -> builder.compare(LlvmBuilder.ICMP_EQ, a, b) }
            Opcode.LogicalNot -> emitter.components(operands[0]).map { builder.binary(LlvmBuilder.BINOP_XOR, it, emitter.i1(true)) }
            Opcode.Not -> emitter.components(operands[0]).map {
                normalizeValue(builder.binary(LlvmBuilder.BINOP_XOR, it, allOnes(it.type)), scalar!!)
            }

            Opcode.FNeg -> emitter.components(operands[0]).map { builder.binary(LlvmBuilder.BINOP_SUB, negativeZero(it.type), it) }
            Opcode.INeg -> emitter.components(operands[0]).map {
                normalizeValue(builder.binary(LlvmBuilder.BINOP_SUB, zero(it.type), it), scalar!!)
            }

            Opcode.IEqual -> compare(instruction, LlvmBuilder.ICMP_EQ)
            Opcode.INotEqual -> compare(instruction, LlvmBuilder.ICMP_NE)
            Opcode.SLess -> compare(instruction, LlvmBuilder.ICMP_SLT)
            Opcode.SLessEqual -> compare(instruction, LlvmBuilder.ICMP_SLE)
            Opcode.SGreater -> compare(instruction, LlvmBuilder.ICMP_SGT)
            Opcode.SGreaterEqual -> compare(instruction, LlvmBuilder.ICMP_SGE)
            Opcode.ULess -> compare(instruction, LlvmBuilder.ICMP_ULT)
            Opcode.ULessEqual -> compare(instruction, LlvmBuilder.ICMP_ULE)
            Opcode.UGreater -> compare(instruction, LlvmBuilder.ICMP_UGT)
            Opcode.UGreaterEqual -> compare(instruction, LlvmBuilder.ICMP_UGE)
            Opcode.FEqual -> compare(instruction, LlvmBuilder.FCMP_OEQ)
            Opcode.FNotEqual -> compare(instruction, LlvmBuilder.FCMP_UNE)
            Opcode.FLess -> compare(instruction, LlvmBuilder.FCMP_OLT)
            Opcode.FLessEqual -> compare(instruction, LlvmBuilder.FCMP_OLE)
            Opcode.FGreater -> compare(instruction, LlvmBuilder.FCMP_OGT)
            Opcode.FGreaterEqual -> compare(instruction, LlvmBuilder.FCMP_OGE)
            Opcode.Select -> {
                val condition = emitter.components(operands[0])
                val whenTrue = emitter.components(operands[1])
                val whenFalse = emitter.components(operands[2])
                whenTrue.indices.map { builder.select(condition.getOrElse(it) { condition[0] }, whenTrue[it], whenFalse[it]) }
            }

            Opcode.SToF, Opcode.UToF, Opcode.FToS, Opcode.FToU, Opcode.FConvert, Opcode.SConvert, Opcode.UConvert ->
                convert(opcode, operands[0], type)

            Opcode.Bitcast -> bitcast(operands[0], type)
            Opcode.CompositeConstruct -> operands.flatMap { emitter.components(it) }
            Opcode.CompositeExtract -> {
                val (offset, count) = slice(operands[0].type, instruction.literals)
                emitter.components(operands[0]).subList(offset, offset + count)
            }

            Opcode.CompositeInsert -> {
                val composite = emitter.components(operands[1]).toMutableList()
                val value = emitter.components(operands[0])
                val (offset, _) = slice(operands[1].type, instruction.literals)
                value.forEachIndexed { index, component -> composite[offset + index] = component }
                composite
            }

            Opcode.VectorShuffle -> {
                val all = emitter.components(operands[0]) + emitter.components(operands[1])
                instruction.literals.map { all[it] }
            }

            Opcode.VectorExtractDynamic -> {
                val components = emitter.components(operands[0])
                val index = emitter.index32(emitter.scalar(operands[1]))
                var result = components[0]
                for (i in 1 until components.size) {
                    result = builder.select(builder.compare(LlvmBuilder.ICMP_EQ, index, emitter.i32(i)), components[i], result)
                }
                listOf(result)
            }

            Opcode.VectorInsertDynamic -> {
                val components = emitter.components(operands[0])
                val value = emitter.scalar(operands[1])
                val index = emitter.index32(emitter.scalar(operands[2]))
                components.mapIndexed { i, old -> builder.select(builder.compare(LlvmBuilder.ICMP_EQ, index, emitter.i32(i)), value, old) }
            }

            Opcode.MatrixTimesVector -> {
                val matrix = operands[0].type as IrMatrix
                val m = emitter.components(operands[0])
                val v = emitter.components(operands[1])
                List(matrix.rows) { row -> sum(List(matrix.columns) { column -> multiply(m[column * matrix.rows + row], v[column]) }) }
            }

            Opcode.VectorTimesMatrix -> {
                val matrix = operands[1].type as IrMatrix
                val v = emitter.components(operands[0])
                val m = emitter.components(operands[1])
                List(matrix.columns) { column -> sum(List(matrix.rows) { row -> multiply(v[row], m[column * matrix.rows + row]) }) }
            }

            Opcode.MatrixTimesMatrix -> {
                val left = operands[0].type as IrMatrix
                val right = operands[1].type as IrMatrix
                val a = emitter.components(operands[0])
                val b = emitter.components(operands[1])
                List(right.columns * left.rows) { index ->
                    val column = index / left.rows
                    val row = index % left.rows
                    sum(List(left.columns) { k -> multiply(a[k * left.rows + row], b[column * right.rows + k]) })
                }
            }

            Opcode.MatrixTimesScalar, Opcode.VectorTimesScalar -> {
                val factor = emitter.scalar(operands[1])
                emitter.components(operands[0]).map { multiply(it, factor) }
            }

            else -> {
                emitter.error("unsupported instruction ${instruction.opcode}")
                emitter.scalars(type).map { emitter.undef(emitter.scalarType(it)) }
            }
        }
    }

    private fun multiply(a: LlvmValue, b: LlvmValue): LlvmValue = builder.binary(LlvmBuilder.BINOP_MUL, a, b)

    private fun sum(values: List<LlvmValue>): LlvmValue = values.reduce { a, b -> builder.binary(LlvmBuilder.BINOP_ADD, a, b) }

    private fun binary(instruction: Instruction, code: Int, normalize: Boolean): List<LlvmValue> {
        val scalar = instruction.type.scalar
        return componentwise2(instruction) { a, b ->
            val result = builder.binary(code, a, b)
            if (normalize) normalizeValue(result, scalar) else result
        }
    }

    private inline fun componentwise2(instruction: Instruction, operation: (LlvmValue, LlvmValue) -> LlvmValue): List<LlvmValue> {
        val left = emitter.components(instruction.operands[0])
        val right = emitter.components(instruction.operands[1])
        return left.indices.map { operation(left[it], right.getOrElse(it) { right[0] }) }
    }

    private fun compare(instruction: Instruction, predicate: Int): List<LlvmValue> =
        componentwise2(instruction) { a, b -> builder.compare(predicate, a, b) }

    fun normalizeValue(value: LlvmValue, scalar: IrScalar): LlvmValue {
        if (scalar !is IrInt || scalar.bits != 8) return value
        return if (scalar.signed) {
            builder.binary(LlvmBuilder.BINOP_ASHR, builder.binary(LlvmBuilder.BINOP_SHL, value, emitter.i32(24)), emitter.i32(24))
        } else {
            builder.binary(LlvmBuilder.BINOP_AND, value, emitter.i32(0xFF))
        }
    }

    private fun allOnes(type: LlvmType): LlvmValue = LlvmConstantInt(type as LlvmIntType, -1)

    private fun zero(type: LlvmType): LlvmValue = when (type) {
        is LlvmIntType -> LlvmConstantInt(type, 0)
        is LlvmFloatType -> LlvmConstantFloat(type, 0)
        else -> emitter.undef(type)
    }

    private fun negativeZero(type: LlvmType): LlvmValue = when ((type as LlvmFloatType).bits) {
        16 -> LlvmConstantFloat(type, 0x8000)
        64 -> LlvmConstantFloat(type, Long.MIN_VALUE)
        else -> LlvmConstantFloat(type, 0x80000000L)
    }

    private fun convert(opcode: Opcode, operand: gg.sona.msl.ir.Value, target: IrType): List<LlvmValue> {
        val source = operand.type.scalar
        val destination = target.scalar
        val destinationType = emitter.scalarType(destination)
        return emitter.components(operand).map { value ->
            when (opcode) {
                Opcode.SToF -> builder.cast(LlvmBuilder.CAST_SITOFP, value, destinationType)
                Opcode.UToF -> builder.cast(LlvmBuilder.CAST_UITOFP, value, destinationType)
                Opcode.FToS -> normalizeValue(builder.cast(LlvmBuilder.CAST_FPTOSI, value, destinationType), destination)
                Opcode.FToU -> normalizeValue(builder.cast(LlvmBuilder.CAST_FPTOUI, value, destinationType), destination)
                Opcode.FConvert -> {
                    val from = (value.type as LlvmFloatType).bits
                    val to = (destinationType as LlvmFloatType).bits
                    when {
                        from == to -> value
                        from > to -> builder.cast(LlvmBuilder.CAST_FPTRUNC, value, destinationType)
                        else -> builder.cast(LlvmBuilder.CAST_FPEXT, value, destinationType)
                    }
                }

                else -> integerConvert(value, source as IrInt, destination as IrInt, opcode == Opcode.SConvert)
            }
        }
    }

    private fun integerConvert(value: LlvmValue, source: IrInt, destination: IrInt, signExtend: Boolean): LlvmValue {
        val sourceBits = source.bits
        val sourceRegister = emitter.scalarType(source) as LlvmIntType
        val destinationRegister = emitter.scalarType(destination) as LlvmIntType
        var current = value
        if (sourceBits == 8) {
            current = if (signExtend) {
                builder.binary(LlvmBuilder.BINOP_ASHR, builder.binary(LlvmBuilder.BINOP_SHL, current, emitter.i32(24)), emitter.i32(24))
            } else {
                builder.binary(LlvmBuilder.BINOP_AND, current, emitter.i32(0xFF))
            }
        }
        if (sourceRegister.bits != destinationRegister.bits) {
            current = when {
                sourceRegister.bits > destinationRegister.bits -> builder.cast(LlvmBuilder.CAST_TRUNC, current, destinationRegister)
                signExtend -> builder.cast(LlvmBuilder.CAST_SEXT, current, destinationRegister)
                else -> builder.cast(LlvmBuilder.CAST_ZEXT, current, destinationRegister)
            }
        }
        return normalizeValue(current, destination)
    }

    private fun bitcast(operand: gg.sona.msl.ir.Value, target: IrType): List<LlvmValue> {
        val source = operand.type.scalar
        val destination = target.scalar
        val components = emitter.components(operand)
        if (source.bits == destination.bits) {
            val destinationType = emitter.scalarType(destination)
            return components.map { if (it.type == destinationType) it else builder.cast(LlvmBuilder.CAST_BITCAST, it, destinationType) }
        }
        val integers = components.map { toInteger(it, source) }
        val sourceBits = source.bits
        val destinationBits = destination.bits
        val result = ArrayList<LlvmValue>()
        if (sourceBits > destinationBits) {
            val pieces = sourceBits / destinationBits
            val pieceType = LlvmIntType(maxOf(destinationBits, 16).let { if (destinationBits == 8) 32 else it })
            for (value in integers) {
                for (piece in 0 until pieces) {
                    val shifted = if (piece == 0) value else builder.binary(LlvmBuilder.BINOP_LSHR, value, shiftConstant(value.type, piece * destinationBits))
                    var truncated: LlvmValue = if ((value.type as LlvmIntType).bits == pieceType.bits) shifted else builder.cast(LlvmBuilder.CAST_TRUNC, shifted, pieceType)
                    if (destinationBits == 8) truncated = builder.binary(LlvmBuilder.BINOP_AND, truncated, emitter.i32(0xFF))
                    result.add(fromInteger(truncated, destination))
                }
            }
        } else {
            val pieces = destinationBits / sourceBits
            val wide = LlvmIntType(destinationBits)
            for (group in integers.chunked(pieces)) {
                var combined: LlvmValue = LlvmConstantInt(wide, 0)
                group.forEachIndexed { piece, value ->
                    val masked = if (sourceBits == 8) builder.binary(LlvmBuilder.BINOP_AND, value, emitter.i32(0xFF)) else value
                    val extended = if ((masked.type as LlvmIntType).bits == destinationBits) masked else builder.cast(LlvmBuilder.CAST_ZEXT, masked, wide)
                    val shifted = if (piece == 0) extended else builder.binary(LlvmBuilder.BINOP_SHL, extended, shiftConstant(wide, piece * sourceBits))
                    combined = builder.binary(LlvmBuilder.BINOP_OR, combined, shifted)
                }
                result.add(fromInteger(combined, destination))
            }
        }
        return result
    }

    private fun shiftConstant(type: LlvmType, amount: Int): LlvmValue = LlvmConstantInt(type as LlvmIntType, amount.toLong())

    private fun toInteger(value: LlvmValue, scalar: IrScalar): LlvmValue = when (scalar) {
        is IrFloat -> builder.cast(LlvmBuilder.CAST_BITCAST, value, LlvmIntType(scalar.bits))
        else -> value
    }

    private fun fromInteger(value: LlvmValue, scalar: IrScalar): LlvmValue = when (scalar) {
        is IrFloat -> builder.cast(LlvmBuilder.CAST_BITCAST, value, emitter.scalarType(scalar))
        is IrInt -> if (scalar.bits == 8) normalizeValue(value, scalar) else value
        is IrBool -> builder.compare(LlvmBuilder.ICMP_NE, value, LlvmConstantInt(value.type as LlvmIntType, 0))
    }

    private fun slice(type: IrType, path: IntArray): Pair<Int, Int> {
        var offset = 0
        var current = type
        for (index in path) {
            when (current) {
                is IrStruct -> for (member in 0 until index) offset += emitter.scalarCount(current.members[member].type)
                is IrArray -> offset += index * emitter.scalarCount(current.element)
                is IrMatrix -> offset += index * current.rows
                is IrVector -> offset += index
                else -> Unit
            }
            current = IrBuilder.memberType(current, index)
        }
        return offset to emitter.scalarCount(current)
    }

}
