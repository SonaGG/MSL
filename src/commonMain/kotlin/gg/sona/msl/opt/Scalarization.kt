package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value

class Scalarization(private val isNative: (Intrinsic, Instruction) -> Boolean) {
    private lateinit var builder: IrBuilder
    private val replacements = HashMap<Value, Value>()

    fun run(function: IrFunction): Boolean {
        builder = IrBuilder(function)
        replacements.clear()
        for (block in function.blocks.toList()) {
            for (instruction in block.instructions.toList()) {
                for (i in instruction.operands.indices) instruction.operands[i] = IrRewriter.resolve(instruction.operands[i], replacements)
                val replacement = if (instruction.opcode == Opcode.Phi) phi(block, instruction) else scalarize(instruction)
                if (replacement != null) replacements[instruction] = replacement
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun lane(value: Value, index: Int): Value {
        val type = value.type
        if (type !is IrVector) return value
        return when (value) {
            is ConstantComposite -> value.elements[index]
            is ConstantNull -> Constants.zero(type.element)
            is Undef -> Undef(type.element)
            is Instruction -> if (value.opcode == Opcode.CompositeConstruct) {
                var remaining = index
                for (part in value.operands) {
                    val width = part.type.componentCount
                    if (remaining < width) return lane(part, remaining)
                    remaining -= width
                }
                builder.extract(value, index)
            } else {
                builder.extract(value, index)
            }

            else -> builder.extract(value, index)
        }
    }

    private fun element(matrix: Value, column: Int, row: Int): Value = when (matrix) {
        is ConstantComposite -> lane(matrix.elements[column], row)
        is ConstantNull -> Constants.zero((matrix.type as IrMatrix).column.element)
        is Undef -> Undef((matrix.type as IrMatrix).column.element)
        is Instruction -> if (matrix.opcode == Opcode.CompositeConstruct) lane(matrix.operands[column], row) else builder.extract(matrix, column, row)
        else -> builder.extract(matrix, column, row)
    }

    private fun column(matrix: Value, column: Int): List<Value> = List((matrix.type as IrMatrix).rows) { element(matrix, column, it) }

    private fun lanes(value: Value, count: Int): List<Value> = List(count) { lane(value, it) }

    private fun vector(type: IrVector, lanes: List<Value>): Value = builder.construct(type, lanes)

    private fun matrix(type: IrMatrix, columns: List<List<Value>>): Value = builder.construct(type, columns.map { vector(type.column, it) })

    private fun sum(terms: List<Value>, type: IrScalar): Value {
        val add = if (type is IrFloat) Opcode.FAdd else Opcode.IAdd
        return terms.drop(1).fold(terms[0]) { acc, term -> builder.binary(add, type, acc, term) }
    }

    private fun dot(a: List<Value>, b: List<Value>, type: IrScalar): Value = sum(a.indices.map { builder.binary(Opcode.FMul, type, a[it], b[it]) }, type)

    private fun scalarize(instruction: Instruction): Value? {
        val type = instruction.type
        val ops = instruction.operands
        if (packable(type)) return null
        builder.positionBefore(instruction)
        return when (instruction.opcode) {
            in ELEMENTWISE -> when (type) {
                is IrVector -> if (ops.all { it.type is IrScalar || it.type.componentCount == type.count }) {
                    vector(type, List(type.count) { i -> builder.emit(instruction.opcode, type.element, ops.map { lane(it, i) }) })
                } else {
                    null
                }

                is IrMatrix -> if (ops.all { it.type == type }) {
                    matrix(type, List(type.columns) { c -> List(type.rows) { r -> builder.emit(instruction.opcode, type.column.element, ops.map { element(it, c, r) }) } })
                } else {
                    null
                }

                else -> null
            }

            Opcode.Bitcast -> if (type is IrVector && ops[0].type is IrVector && ops[0].type.componentCount == type.count) {
                vector(type, List(type.count) { i -> builder.emit(Opcode.Bitcast, type.element, listOf(lane(ops[0], i))) })
            } else {
                null
            }

            Opcode.Select -> when (type) {
                is IrVector -> vector(type, List(type.count) { i -> builder.select(lane(ops[0], i), lane(ops[1], i), lane(ops[2], i)) })
                else -> null
            }

            Opcode.CompositeInsert -> if (type is IrVector && instruction.literals.size == 1) {
                val target = instruction.literals[0]
                vector(type, List(type.count) { i -> if (i == target) ops[0] else lane(ops[1], i) })
            } else {
                null
            }

            Opcode.VectorTimesScalar -> vector(type as IrVector, List(type.count) { i -> builder.binary(Opcode.FMul, type.element, lane(ops[0], i), ops[1]) })
            Opcode.MatrixTimesScalar -> {
                val matrix = type as IrMatrix
                matrix(matrix, List(matrix.columns) { c -> List(matrix.rows) { r -> builder.binary(Opcode.FMul, matrix.column.element, element(ops[0], c, r), ops[1]) } })
            }

            Opcode.MatrixTimesVector -> {
                val matrix = ops[0].type as IrMatrix
                val v = lanes(ops[1], matrix.columns)
                val result = type as IrVector
                vector(result, List(matrix.rows) { r -> dot(List(matrix.columns) { c -> element(ops[0], c, r) }, v, result.element) })
            }

            Opcode.VectorTimesMatrix -> {
                val matrix = ops[1].type as IrMatrix
                val v = lanes(ops[0], matrix.rows)
                val result = type as IrVector
                vector(result, List(matrix.columns) { c -> dot(v, column(ops[1], c), result.element) })
            }

            Opcode.MatrixTimesMatrix -> {
                val left = ops[0].type as IrMatrix
                val right = ops[1].type as IrMatrix
                val result = type as IrMatrix
                matrix(result, List(right.columns) { c ->
                    val v = column(ops[1], c)
                    List(left.rows) { r -> dot(List(left.columns) { k -> element(ops[0], k, r) }, v, result.column.element) }
                })
            }

            Opcode.Intrinsic -> intrinsic(instruction)
            else -> null
        }
    }

    private fun packable(type: IrType): Boolean = type is IrVector && (type.element as? IrFloat)?.bits == 16

    private fun intrinsic(instruction: Instruction): Value? {
        val intrinsic = instruction.intrinsic!!
        val type = instruction.type
        val ops = instruction.operands
        if (!isNative(intrinsic, instruction) && intrinsic !in EXPANDED) return null
        return when (intrinsic) {
            in LANEWISE -> if (type is IrVector && instruction.literals.isEmpty()) {
                vector(type, List(type.count) { i -> builder.intrinsic(intrinsic, type.element, ops.map { lane(it, i) }) })
            } else {
                null
            }

            Intrinsic.Dot -> {
                val vector = ops[0].type as? IrVector ?: return null
                dot(lanes(ops[0], vector.count), lanes(ops[1], vector.count), type as IrScalar)
            }

            Intrinsic.All, Intrinsic.Any -> {
                val vector = ops[0].type as? IrVector ?: return null
                val opcode = if (intrinsic == Intrinsic.All) Opcode.LogicalAnd else Opcode.LogicalOr
                lanes(ops[0], vector.count).reduce { acc, lane -> builder.binary(opcode, IrBool, acc, lane) }
            }

            Intrinsic.Length -> {
                val vector = ops[0].type as? IrVector ?: return null
                if (!native(Intrinsic.Sqrt, instruction)) return null
                val v = lanes(ops[0], vector.count)
                builder.intrinsic(Intrinsic.Sqrt, type, listOf(dot(v, v, vector.element)))
            }

            Intrinsic.Distance -> {
                val vector = ops[0].type as? IrVector ?: return null
                if (!native(Intrinsic.Sqrt, instruction)) return null
                val v = List(vector.count) { builder.binary(Opcode.FSub, vector.element, lane(ops[0], it), lane(ops[1], it)) }
                builder.intrinsic(Intrinsic.Sqrt, type, listOf(dot(v, v, vector.element)))
            }

            Intrinsic.Normalize -> {
                val vector = type as? IrVector ?: return null
                if (!native(Intrinsic.Rsqrt, instruction)) return null
                val v = lanes(ops[0], vector.count)
                val scale = builder.intrinsic(Intrinsic.Rsqrt, vector.element, listOf(dot(v, v, vector.element)))
                vector(vector, v.map { builder.binary(Opcode.FMul, vector.element, it, scale) })
            }

            Intrinsic.Cross -> {
                val vector = type as? IrVector ?: return null
                val a = lanes(ops[0], 3)
                val b = lanes(ops[1], 3)
                val element = vector.element
                fun term(i: Int, j: Int) = builder.binary(
                    Opcode.FSub, element,
                    builder.binary(Opcode.FMul, element, a[i], b[j]),
                    builder.binary(Opcode.FMul, element, a[j], b[i]),
                )
                vector(vector, listOf(term(1, 2), term(2, 0), term(0, 1)))
            }

            Intrinsic.Reflect -> {
                val vector = type as? IrVector ?: return null
                val element = vector.element
                val i = lanes(ops[0], vector.count)
                val n = lanes(ops[1], vector.count)
                val scale = builder.binary(Opcode.FMul, element, dot(n, i, element), Constants.scalar(element, 2.0))
                vector(vector, i.indices.map { builder.binary(Opcode.FSub, element, i[it], builder.binary(Opcode.FMul, element, n[it], scale)) })
            }

            else -> null
        }
    }

    private fun native(intrinsic: Intrinsic, context: Instruction): Boolean = isNative(intrinsic, context)

    private fun phi(block: Block, instruction: Instruction): Value? {
        val type = instruction.type as? IrVector ?: return null
        if (packable(type)) return null
        val lanes = List(type.count) { i ->
            val incoming = instruction.targets.indices.map { k ->
                val predecessor = instruction.targets[k]
                val terminator = predecessor.terminator ?: return null
                builder.positionBefore(terminator)
                lane(instruction.operands[k], i)
            }
            val phi = Instruction(Opcode.Phi, type.element, incoming)
            phi.targets.addAll(instruction.targets)
            phi
        }
        val index = block.instructions.indexOf(instruction)
        for (phi in lanes.asReversed()) {
            phi.block = block
            block.instructions.add(index + 1, phi)
        }
        val firstNonPhi = block.instructions.indexOfFirst { it.opcode != Opcode.Phi }
        builder.positionBefore(block.instructions[firstNonPhi])
        return vector(type, lanes)
    }

    private companion object {
        val ELEMENTWISE = setOf(
            Opcode.IAdd, Opcode.ISub, Opcode.IMul, Opcode.SDiv, Opcode.UDiv, Opcode.SRem, Opcode.URem,
            Opcode.FAdd, Opcode.FSub, Opcode.FMul, Opcode.FDiv, Opcode.FRem, Opcode.FNeg, Opcode.INeg,
            Opcode.And, Opcode.Or, Opcode.Xor, Opcode.Not, Opcode.Shl, Opcode.LShr, Opcode.AShr,
            Opcode.LogicalAnd, Opcode.LogicalOr, Opcode.LogicalNot, Opcode.LogicalEqual, Opcode.LogicalNotEqual,
            Opcode.IEqual, Opcode.INotEqual, Opcode.SLess, Opcode.SLessEqual, Opcode.SGreater, Opcode.SGreaterEqual,
            Opcode.ULess, Opcode.ULessEqual, Opcode.UGreater, Opcode.UGreaterEqual,
            Opcode.FEqual, Opcode.FNotEqual, Opcode.FLess, Opcode.FLessEqual, Opcode.FGreater, Opcode.FGreaterEqual,
            Opcode.SToF, Opcode.UToF, Opcode.FToS, Opcode.FToU, Opcode.FConvert, Opcode.SConvert, Opcode.UConvert,
        )

        val LANEWISE = setOf(
            Intrinsic.FAbs, Intrinsic.FSign, Intrinsic.Floor, Intrinsic.Ceil, Intrinsic.Round, Intrinsic.Rint, Intrinsic.Trunc,
            Intrinsic.Fract, Intrinsic.Sqrt, Intrinsic.Rsqrt, Intrinsic.Sin, Intrinsic.Cos, Intrinsic.Tan, Intrinsic.Asin,
            Intrinsic.Acos, Intrinsic.Atan, Intrinsic.Atan2, Intrinsic.Sinh, Intrinsic.Cosh, Intrinsic.Tanh, Intrinsic.Asinh,
            Intrinsic.Acosh, Intrinsic.Atanh, Intrinsic.Exp, Intrinsic.Exp2, Intrinsic.Log, Intrinsic.Log2, Intrinsic.Pow,
            Intrinsic.Powr, Intrinsic.Fma, Intrinsic.FMin, Intrinsic.FMax, Intrinsic.FClamp, Intrinsic.Mix, Intrinsic.Step,
            Intrinsic.Smoothstep, Intrinsic.Saturate, Intrinsic.SAbs, Intrinsic.SMin, Intrinsic.SMax, Intrinsic.UMin,
            Intrinsic.UMax, Intrinsic.SClamp, Intrinsic.UClamp, Intrinsic.IsNan, Intrinsic.IsInf, Intrinsic.IsFinite,
            Intrinsic.IsNormal, Intrinsic.Popcount, Intrinsic.ReverseBits, Intrinsic.Clz, Intrinsic.Ctz, Intrinsic.Ldexp,
        )

        val EXPANDED = setOf(Intrinsic.Length, Intrinsic.Distance, Intrinsic.Normalize, Intrinsic.Cross, Intrinsic.Reflect)
    }
}
