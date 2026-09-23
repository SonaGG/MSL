package gg.sona.msl.opt

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.SpecConstant
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.util.HalfFloat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class IrInterpreter(private val entry: EntryPoint, seed: Long, private val limit: Int = 2_000_000) {
    private val random = Random(seed)
    private val cells = HashMap<GlobalVariable, Cell>()
    private val values = HashMap<Value, Any>()
    private val writes = LinkedHashMap<String, Any>()
    private var discarded = false
    private var steps = 0

    fun run(): Execution {
        for (global in entry.interfaceVariables) cells[global] = Cell(global.valueType, initial(global), global.name)
        execute()
        val state = LinkedHashMap<String, Any>()
        for ((global, cell) in cells) {
            if (global.storage == StorageClass.Output || global.storage == StorageClass.StorageBuffer) state[global.name] = cell.value
        }
        state.putAll(writes)
        return Execution(state, discarded)
    }

    private fun initial(global: GlobalVariable): Any = when (global.storage) {
        StorageClass.Input, StorageClass.Uniform, StorageClass.StorageBuffer, StorageClass.PushConstant -> randomValue(global.valueType)
        StorageClass.UniformConstant -> handles(global.valueType, global.name)
        StorageClass.Private -> global.initializer?.let { constant(it) } ?: zero(global.valueType)
        else -> zero(global.valueType)
    }

    private fun handles(type: IrType, name: String): Any =
        if (type is IrArray) MutableList(if (type.isRuntime) 16 else type.length) { ResourceHandle(name, it.toLong()) } else ResourceHandle(name, 0)

    private fun randomValue(type: IrType): Any = when (type) {
        is IrFloat -> round(type, random.nextDouble(-2.0, 2.0))
        is IrInt -> normalize(type, random.nextLong(0, 16))
        is IrBool -> random.nextBoolean()
        is IrVector -> MutableList(type.count) { randomValue(type.element) }
        is IrMatrix -> MutableList(type.columns) { randomValue(type.column) }
        is IrArray -> MutableList(if (type.isRuntime) 16 else type.length) { randomValue(type.element) }
        is IrStruct -> MutableList(type.members.size) { randomValue(type.members[it].type) }
        else -> zero(type)
    }

    private fun zero(type: IrType): Any = when (type) {
        is IrFloat -> 0.0
        is IrInt -> 0L
        is IrBool -> false
        is IrVector -> MutableList(type.count) { zero(type.element) }
        is IrMatrix -> MutableList(type.columns) { zero(type.column) }
        is IrArray -> MutableList(if (type.isRuntime) 16 else type.length) { zero(type.element) }
        is IrStruct -> MutableList(type.members.size) { zero(type.members[it].type) }
        is IrImage, IrSampler -> ResourceHandle("undef", 0)
        else -> 0L
    }

    private fun execute() {
        val function = entry.function
        var block: Block = function.entry
        var previous: Block? = null
        while (true) {
            val phis = block.phis.map { phi ->
                val index = phi.targets.indexOf(previous)
                phi to (if (index < 0) zero(phi.type) else operand(phi.operands[index]))
            }
            for ((phi, value) in phis) values[phi] = value
            var next: Block? = null
            for (instruction in block.instructions) {
                if (instruction.opcode == Opcode.Phi) continue
                if (++steps > limit) throw InterpreterLimit("step limit")
                when (instruction.opcode) {
                    Opcode.Branch -> next = instruction.targets[0]
                    Opcode.CondBranch -> next = if (operand(instruction.operands[0]) as Boolean) instruction.targets[0] else instruction.targets[1]
                    Opcode.Switch -> {
                        val selector = operand(instruction.operands[0]) as Long
                        val index = instruction.literals.indexOfFirst { it.toLong() == selector || normalize(instruction.operands[0].type as IrInt, it.toLong()) == selector }
                        next = if (index < 0) instruction.targets[0] else instruction.targets[index + 1]
                    }

                    Opcode.Return, Opcode.Unreachable -> return
                    else -> {
                        evaluate(instruction)?.let { values[instruction] = it }
                        if (discarded) return
                    }
                }
                if (next != null) break
            }
            previous = block
            block = next ?: return
        }
    }

    private fun operand(value: Value): Any = values[value] ?: when (value) {
        is GlobalVariable -> Pointer(cells.getOrPut(value) { Cell(value.valueType, initial(value), value.name) }, emptyList())
        is SpecConstant -> constant(value.default)
        is gg.sona.msl.ir.IrConstant -> constant(value)
        else -> throw InterpreterLimit("unbound value $value")
    }

    private fun constant(value: gg.sona.msl.ir.IrConstant): Any = when (value) {
        is ConstantScalar -> when (val type = value.type) {
            is IrFloat -> value.asDouble
            is IrBool -> value.asBoolean
            is IrInt -> normalize(type, value.bits)
        }

        is ConstantComposite -> value.elements.map { constant(it) }.toMutableList()
        is ConstantNull, is Undef -> zero(value.type)
    }

    private fun evaluate(instruction: Instruction): Any? {
        val type = instruction.type
        val ops = instruction.operands
        fun arg(index: Int) = operand(ops[index])
        return when (instruction.opcode) {
            Opcode.Variable -> {
                val pointee = (type as IrPointer).pointee
                Pointer(Cell(pointee, ops.firstOrNull()?.let { copy(operand(it)) } ?: zero(pointee), "local"), emptyList())
            }

            Opcode.Load -> copy(load(arg(0) as Pointer))
            Opcode.Store -> {
                store(arg(0) as Pointer, copy(arg(1)))
                null
            }

            Opcode.AccessChain -> {
                val base = arg(0) as Pointer
                Pointer(base.cell, base.path + ops.drop(1).map { operand(it) as Long })
            }

            Opcode.PtrOffset -> {
                val base = arg(0) as Pointer
                val offset = arg(1) as Long
                if (base.path.isEmpty()) throw InterpreterLimit("pointer arithmetic on a whole variable")
                Pointer(base.cell, base.path.dropLast(1) + (base.path.last() + offset))
            }

            Opcode.Select -> {
                val condition = arg(0)
                if (condition is List<*>) {
                    val a = arg(1) as List<*>
                    val b = arg(2) as List<*>
                    condition.indices.map { if (condition[it] as Boolean) a[it]!! else b[it]!! }.toMutableList()
                } else {
                    if (condition as Boolean) arg(1) else arg(2)
                }
            }

            Opcode.CompositeConstruct -> construct(type, ops.map { operand(it) })
            Opcode.CompositeExtract -> {
                var current: Any = arg(0)
                for (index in instruction.literals) current = (current as List<*>)[index]!!
                copy(current)
            }

            Opcode.CompositeInsert -> {
                val result = copy(arg(1))
                setPath(result, instruction.literals.map { it.toLong() }, copy(arg(0)))
                result
            }

            Opcode.VectorShuffle -> {
                val a = arg(0) as List<*>
                val b = arg(1) as List<*>
                instruction.literals.map { if (it < a.size) a[it]!! else b[it - a.size]!! }.toMutableList()
            }

            Opcode.VectorExtractDynamic -> {
                val vector = arg(0) as List<*>
                val index = arg(1) as Long
                if (index in vector.indices.map { it.toLong() }) vector[index.toInt()]!! else zero(type)
            }

            Opcode.VectorInsertDynamic -> {
                val vector = (arg(0) as List<*>).map { it!! }.toMutableList()
                val index = arg(2) as Long
                if (index >= 0 && index < vector.size) vector[index.toInt()] = arg(1)
                vector
            }

            Opcode.MatrixTimesVector -> {
                val matrix = arg(0) as List<*>
                val vector = arg(1) as List<*>
                val rows = (matrix[0] as List<*>).size
                MutableList(rows) { row -> round(type.scalar, matrix.indices.sumOf { column -> ((matrix[column] as List<*>)[row] as Double) * (vector[column] as Double) }) }
            }

            Opcode.VectorTimesMatrix -> {
                val vector = arg(0) as List<*>
                val matrix = arg(1) as List<*>
                MutableList(matrix.size) { column -> round(type.scalar, vector.indices.sumOf { row -> (vector[row] as Double) * ((matrix[column] as List<*>)[row] as Double) }) }
            }

            Opcode.MatrixTimesMatrix -> {
                val left = arg(0) as List<*>
                val right = arg(1) as List<*>
                val rows = (left[0] as List<*>).size
                MutableList(right.size) { column ->
                    MutableList(rows) { row ->
                        round(type.scalar, left.indices.sumOf { k -> ((left[k] as List<*>)[row] as Double) * ((right[column] as List<*>)[k] as Double) })
                    }
                }
            }

            Opcode.MatrixTimesScalar -> {
                val scalar = arg(1) as Double
                (arg(0) as List<*>).map { column -> (column as List<*>).map { round(type.scalar, (it as Double) * scalar) }.toMutableList() }.toMutableList()
            }

            Opcode.VectorTimesScalar -> {
                val scalar = arg(1) as Double
                (arg(0) as List<*>).map { round(type.scalar, (it as Double) * scalar) }.toMutableList()
            }

            Opcode.Intrinsic -> intrinsic(instruction)
            Opcode.Bitcast -> bitcast(arg(0), ops[0].type, type)
            else -> lanes(type, ops.map { operand(it) }) { values, scalarType -> scalarOp(instruction, values, scalarType, ops[0].type.let { if (it is IrVector) it.element else it } as IrScalar) }
        }
    }

    private fun bitcast(value: Any, source: IrType, target: IrType): Any {
        var stream = java.math.BigInteger.ZERO
        var shift = 0
        for ((bits, width) in toBits(value, source.scalar)) {
            stream = stream.or(java.math.BigInteger.valueOf(bits).and(java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE)).shiftLeft(shift))
            shift += width
        }
        val scalar = target.scalar
        val width = if (scalar is IrBool) 32 else scalar.bits
        fun lane(index: Int): Any {
            val bits = stream.shiftRight(index * width).and(java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE)).toLong()
            return fromBits(scalar, listOf(bits to 64))
        }
        return if (target is IrVector) MutableList(target.count) { lane(it) } else lane(0)
    }

    private fun construct(type: IrType, parts: List<Any>): Any {
        if (type is IrVector) {
            val result = ArrayList<Any>()
            for (part in parts) if (part is List<*>) part.forEach { result.add(it!!) } else result.add(part)
            return result.toMutableList()
        }
        return parts.map { copy(it) }.toMutableList()
    }

    private fun lanes(type: IrType, operands: List<Any>, operation: (List<Any>, IrScalar) -> Any): Any {
        if (type is IrVector) {
            return MutableList(type.count) { lane -> operation(operands.map { if (it is List<*>) it[lane]!! else it }, type.element) }
        }
        return operation(operands, type as IrScalar)
    }

    private fun scalarOp(instruction: Instruction, v: List<Any>, type: IrScalar, source: IrScalar): Any {
        fun f(i: Int) = v[i] as Double
        fun l(i: Int) = v[i] as Long
        fun b(i: Int) = v[i] as Boolean
        fun int(value: Long) = normalize(type as IrInt, value)
        fun float(value: Double) = round(type, value)
        fun unsigned(i: Int): Long = l(i)
        val bits = (source as? IrInt)?.bits ?: 32
        return when (instruction.opcode) {
            Opcode.IAdd -> int(l(0) + l(1))
            Opcode.ISub -> int(l(0) - l(1))
            Opcode.IMul -> int(l(0) * l(1))
            Opcode.SDiv -> if (l(1) == 0L) int(0) else int(l(0) / l(1))
            Opcode.UDiv -> if (l(1) == 0L) int(0) else int(java.lang.Long.divideUnsigned(unsigned(0), unsigned(1)))
            Opcode.SRem -> if (l(1) == 0L) int(0) else int(l(0) % l(1))
            Opcode.URem -> if (l(1) == 0L) int(0) else int(java.lang.Long.remainderUnsigned(unsigned(0), unsigned(1)))
            Opcode.FAdd -> float(f(0) + f(1))
            Opcode.FSub -> float(f(0) - f(1))
            Opcode.FMul -> float(f(0) * f(1))
            Opcode.FDiv -> float(f(0) / f(1))
            Opcode.FRem -> float(f(0) - f(1) * kotlin.math.truncate(f(0) / f(1)))
            Opcode.FNeg -> float(-f(0))
            Opcode.INeg -> int(-l(0))
            Opcode.And -> if (type is IrBool) b(0) && b(1) else int(l(0) and l(1))
            Opcode.Or -> if (type is IrBool) b(0) || b(1) else int(l(0) or l(1))
            Opcode.Xor -> if (type is IrBool) b(0) xor b(1) else int(l(0) xor l(1))
            Opcode.Not -> if (type is IrBool) !b(0) else int(l(0).inv())
            Opcode.Shl -> int(l(0) shl (l(1) % bits).toInt())
            Opcode.LShr -> int(mask(l(0), bits) ushr (l(1) % bits).toInt())
            Opcode.AShr -> int(signExtend(l(0), bits) shr (l(1) % bits).toInt())
            Opcode.LogicalAnd -> b(0) && b(1)
            Opcode.LogicalOr -> b(0) || b(1)
            Opcode.LogicalNot -> !b(0)
            Opcode.LogicalEqual -> b(0) == b(1)
            Opcode.LogicalNotEqual -> b(0) != b(1)
            Opcode.IEqual -> l(0) == l(1)
            Opcode.INotEqual -> l(0) != l(1)
            Opcode.SLess -> signExtend(l(0), bits) < signExtend(l(1), bits)
            Opcode.SLessEqual -> signExtend(l(0), bits) <= signExtend(l(1), bits)
            Opcode.SGreater -> signExtend(l(0), bits) > signExtend(l(1), bits)
            Opcode.SGreaterEqual -> signExtend(l(0), bits) >= signExtend(l(1), bits)
            Opcode.ULess -> java.lang.Long.compareUnsigned(mask(l(0), bits), mask(l(1), bits)) < 0
            Opcode.ULessEqual -> java.lang.Long.compareUnsigned(mask(l(0), bits), mask(l(1), bits)) <= 0
            Opcode.UGreater -> java.lang.Long.compareUnsigned(mask(l(0), bits), mask(l(1), bits)) > 0
            Opcode.UGreaterEqual -> java.lang.Long.compareUnsigned(mask(l(0), bits), mask(l(1), bits)) >= 0
            Opcode.FEqual -> f(0) == f(1)
            Opcode.FNotEqual -> f(0) != f(1)
            Opcode.FLess -> f(0) < f(1)
            Opcode.FLessEqual -> f(0) <= f(1)
            Opcode.FGreater -> f(0) > f(1)
            Opcode.FGreaterEqual -> f(0) >= f(1)
            Opcode.SToF -> float(signExtend(l(0), bits).toDouble())
            Opcode.UToF -> float(if (bits == 64 && l(0) < 0) l(0).toULong().toDouble() else mask(l(0), bits).toDouble())
            Opcode.FToS, Opcode.FToU -> int(if (f(0).isNaN()) 0 else f(0).toLong())
            Opcode.FConvert -> float(f(0))
            Opcode.SConvert -> int(signExtend(l(0), bits))
            Opcode.UConvert -> int(mask(l(0), bits))
            Opcode.Bitcast -> fromBits(type, toBits(v[0], source))
            else -> throw InterpreterLimit("unsupported opcode ${instruction.opcode}")
        }
    }

    private fun intrinsic(instruction: Instruction): Any? {
        val intrinsic = instruction.intrinsic!!
        val type = instruction.type
        val ops = instruction.operands
        fun arg(index: Int) = operand(ops[index])
        fun unary(operation: (Double) -> Double) = lanes(type, listOf(arg(0))) { v, t -> round(t, operation(v[0] as Double)) }
        fun binary(operation: (Double, Double) -> Double) = lanes(type, listOf(arg(0), arg(1))) { v, t -> round(t, operation(v[0] as Double, v[1] as Double)) }
        fun ternary(operation: (Double, Double, Double) -> Double) =
            lanes(type, listOf(arg(0), arg(1), arg(2))) { v, t -> round(t, operation(v[0] as Double, v[1] as Double, v[2] as Double)) }

        fun integer(operation: (List<Long>, IrInt) -> Long) = lanes(type, ops.map { operand(it) }) { v, t -> normalize(t as IrInt, operation(v.map { it as Long }, t)) }
        fun vector(index: Int) = (arg(index) as List<*>).map { it as Double }
        return when (intrinsic) {
            Intrinsic.FAbs -> unary { abs(it) }
            Intrinsic.FSign -> unary { if (it > 0) 1.0 else if (it < 0) -1.0 else 0.0 }
            Intrinsic.Floor -> unary { kotlin.math.floor(it) }
            Intrinsic.Ceil -> unary { kotlin.math.ceil(it) }
            Intrinsic.Round -> unary { if (it < 0) -kotlin.math.floor(-it + 0.5) else kotlin.math.floor(it + 0.5) }
            Intrinsic.Rint -> unary { kotlin.math.round(it) }
            Intrinsic.Trunc -> unary { kotlin.math.truncate(it) }
            Intrinsic.Fract -> unary { minOf(it - kotlin.math.floor(it), 0.99999994) }
            Intrinsic.Sqrt -> unary { sqrt(it) }
            Intrinsic.Rsqrt -> unary { 1.0 / sqrt(it) }
            Intrinsic.Sin -> unary { kotlin.math.sin(it) }
            Intrinsic.Cos -> unary { kotlin.math.cos(it) }
            Intrinsic.Tan -> unary { kotlin.math.tan(it) }
            Intrinsic.Asin -> unary { kotlin.math.asin(it) }
            Intrinsic.Acos -> unary { kotlin.math.acos(it) }
            Intrinsic.Atan -> unary { kotlin.math.atan(it) }
            Intrinsic.Sinh -> unary { kotlin.math.sinh(it) }
            Intrinsic.Cosh -> unary { kotlin.math.cosh(it) }
            Intrinsic.Tanh -> unary { kotlin.math.tanh(it) }
            Intrinsic.Asinh -> unary { kotlin.math.asinh(it) }
            Intrinsic.Acosh -> unary { kotlin.math.acosh(it) }
            Intrinsic.Atanh -> unary { kotlin.math.atanh(it) }
            Intrinsic.Exp -> unary { kotlin.math.exp(it) }
            Intrinsic.Exp2 -> unary { Math.pow(2.0, it) }
            Intrinsic.Exp10 -> unary { Math.pow(10.0, it) }
            Intrinsic.Log -> unary { kotlin.math.ln(it) }
            Intrinsic.Log2 -> unary { kotlin.math.log2(it) }
            Intrinsic.Log10 -> unary { kotlin.math.log10(it) }
            Intrinsic.Sinpi -> unary { kotlin.math.sin(it * PI) }
            Intrinsic.Cospi -> unary { kotlin.math.cos(it * PI) }
            Intrinsic.Tanpi -> unary { kotlin.math.tan(it * PI) }
            Intrinsic.Saturate -> unary { it.coerceIn(0.0, 1.0) }
            Intrinsic.Atan2 -> binary { y, x -> kotlin.math.atan2(y, x) }
            Intrinsic.Pow, Intrinsic.Powr -> binary { x, y -> Math.pow(x, y) }
            Intrinsic.FMin -> binary { a, b -> if (a.isNaN()) b else if (b.isNaN()) a else minOf(a, b) }
            Intrinsic.FMax -> binary { a, b -> if (a.isNaN()) b else if (b.isNaN()) a else maxOf(a, b) }
            Intrinsic.Fmod -> binary { a, b -> a - b * kotlin.math.truncate(a / b) }
            Intrinsic.Copysign -> binary { a, b -> Math.copySign(a, b) }
            Intrinsic.Fdim -> binary { a, b -> maxOf(a - b, 0.0) }
            Intrinsic.Step -> binary { edge, x -> if (x < edge) 0.0 else 1.0 }
            Intrinsic.Fma -> ternary { a, b, c -> a * b + c }
            Intrinsic.FClamp -> ternary { x, lo, hi -> minOf(maxOf(x, lo), hi) }
            Intrinsic.Mix -> ternary { a, b, t -> a + (b - a) * t }
            Intrinsic.Smoothstep -> ternary { e0, e1, x ->
                val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
                t * t * (3 - 2 * t)
            }

            Intrinsic.Ldexp -> lanes(type, listOf(arg(0), arg(1))) { v, t -> round(t, (v[0] as Double) * Math.pow(2.0, signExtend(v[1] as Long, 32).toDouble())) }
            Intrinsic.SMin -> integer { v, t -> minOf(signExtend(v[0], t.bits), signExtend(v[1], t.bits)) }
            Intrinsic.SMax -> integer { v, t -> maxOf(signExtend(v[0], t.bits), signExtend(v[1], t.bits)) }
            Intrinsic.UMin -> integer { v, t -> if (java.lang.Long.compareUnsigned(mask(v[0], t.bits), mask(v[1], t.bits)) < 0) v[0] else v[1] }
            Intrinsic.UMax -> integer { v, t -> if (java.lang.Long.compareUnsigned(mask(v[0], t.bits), mask(v[1], t.bits)) > 0) v[0] else v[1] }
            Intrinsic.SClamp -> integer { v, t -> minOf(maxOf(signExtend(v[0], t.bits), signExtend(v[1], t.bits)), signExtend(v[2], t.bits)) }
            Intrinsic.UClamp -> integer { v, t -> minOf(maxOf(mask(v[0], t.bits), mask(v[1], t.bits)), mask(v[2], t.bits)) }
            Intrinsic.SAbs -> integer { v, t -> abs(signExtend(v[0], t.bits)) }
            Intrinsic.Popcount -> integer { v, t -> java.lang.Long.bitCount(mask(v[0], t.bits)).toLong() }
            Intrinsic.Clz -> integer { v, t -> (java.lang.Long.numberOfLeadingZeros(mask(v[0], t.bits)) - (64 - t.bits)).toLong() }
            Intrinsic.Ctz -> integer { v, t -> minOf(java.lang.Long.numberOfTrailingZeros(mask(v[0], t.bits)), t.bits).toLong() }
            Intrinsic.ReverseBits -> integer { v, t -> java.lang.Long.reverse(mask(v[0], t.bits)) ushr (64 - t.bits) }
            Intrinsic.UExtractBits -> integer { v, t -> if (v[2] == 0L) 0 else (mask(v[0], t.bits) ushr v[1].toInt()) and ((1L shl v[2].toInt()) - 1) }
            Intrinsic.SExtractBits -> integer { v, t ->
                if (v[2] == 0L) 0 else (signExtend(v[0], t.bits) shl (64 - v[1].toInt() - v[2].toInt())) shr (64 - v[2].toInt())
            }

            Intrinsic.InsertBits -> integer { v, _ ->
                val m = ((1L shl v[3].toInt()) - 1) shl v[2].toInt()
                (v[0] and m.inv()) or ((v[1] shl v[2].toInt()) and m)
            }

            Intrinsic.SMulHi -> integer { v, t -> (signExtend(v[0], t.bits) * signExtend(v[1], t.bits)) shr t.bits }
            Intrinsic.UMulHi -> integer { v, t -> (mask(v[0], t.bits) * mask(v[1], t.bits)) ushr t.bits }
            Intrinsic.Dot -> round(type.scalar, vector(0).zip(vector(1)).sumOf { it.first * it.second })
            Intrinsic.Length -> round(type.scalar, sqrt(vector(0).sumOf { it * it }))
            Intrinsic.Normalize -> {
                val v = vector(0)
                val length = sqrt(v.sumOf { it * it })
                v.map { round(type.scalar, it / length) }.toMutableList()
            }

            Intrinsic.Cross -> {
                val a = vector(0)
                val b = vector(1)
                mutableListOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]).map { round(type.scalar, it) }.toMutableList()
            }

            Intrinsic.Distance -> round(type.scalar, sqrt(vector(0).zip(vector(1)).sumOf { (it.first - it.second) * (it.first - it.second) }))
            Intrinsic.Reflect -> {
                val i = vector(0)
                val n = vector(1)
                val d = i.zip(n).sumOf { it.first * it.second }
                i.indices.map { round(type.scalar, i[it] - 2 * d * n[it]) }.toMutableList()
            }

            Intrinsic.Refract -> {
                val i = vector(0)
                val n = vector(1)
                val eta = arg(2) as Double
                val d = i.zip(n).sumOf { it.first * it.second }
                val k = 1 - eta * eta * (1 - d * d)
                i.indices.map { if (k < 0) 0.0 else round(type.scalar, eta * i[it] - (eta * d + sqrt(k)) * n[it]) }.toMutableList()
            }

            Intrinsic.FaceForward -> {
                val n = arg(0)
                val d = numbers(arg(2)).zip(numbers(arg(1))).sumOf { it.first * it.second }
                if (d < 0) copy(n) else lanes(type, listOf(n)) { v, t -> round(t, -(v[0] as Double)) }
            }

            Intrinsic.IsNormal -> lanes(type, listOf(arg(0))) { v, _ ->
                val x = abs(v[0] as Double)
                x.isFinite() && x >= (if (ops[0].type.scalar.bits == 16) 6.103515625e-5 else java.lang.Float.MIN_NORMAL.toDouble())
            }

            Intrinsic.Determinant -> {
                val m = (arg(0) as List<*>).map { column -> (column as List<*>).map { it as Double } }
                round(type.scalar, determinant(m))
            }

            Intrinsic.FrexpMantissa -> unary { if (it == 0.0 || !it.isFinite()) it else it / Math.pow(2.0, (Math.getExponent(it) + 1).toDouble()) }
            Intrinsic.FrexpExponent -> lanes(type, listOf(arg(0))) { v, t ->
                val x = v[0] as Double
                normalize(t as IrInt, if (x == 0.0 || !x.isFinite()) 0 else (Math.getExponent(x) + 1).toLong())
            }

            Intrinsic.PackUnorm4x8, Intrinsic.PackSnorm4x8, Intrinsic.PackUnorm2x16, Intrinsic.PackSnorm2x16 -> {
                val v = vector(0)
                val width = if (v.size == 4) 8 else 16
                val signed = intrinsic == Intrinsic.PackSnorm4x8 || intrinsic == Intrinsic.PackSnorm2x16
                var packed = 0L
                v.forEachIndexed { index, x ->
                    val scale = if (signed) (1 shl (width - 1)) - 1 else (1 shl width) - 1
                    val clamped = if (signed) x.coerceIn(-1.0, 1.0) else x.coerceIn(0.0, 1.0)
                    val q = kotlin.math.round(clamped * scale).toLong() and ((1L shl width) - 1)
                    packed = packed or (q shl (index * width))
                }
                normalize(type.scalar as IrInt, packed)
            }

            Intrinsic.UnpackUnorm4x8, Intrinsic.UnpackSnorm4x8, Intrinsic.UnpackUnorm2x16, Intrinsic.UnpackSnorm2x16 -> {
                val packed = arg(0) as Long
                val count = (type as IrVector).count
                val width = if (count == 4) 8 else 16
                val signed = intrinsic == Intrinsic.UnpackSnorm4x8 || intrinsic == Intrinsic.UnpackSnorm2x16
                MutableList(count) { index ->
                    val raw = (packed ushr (index * width)) and ((1L shl width) - 1)
                    val value = if (signed) maxOf(signExtend(raw, width).toDouble() / ((1 shl (width - 1)) - 1), -1.0) else raw.toDouble() / ((1 shl width) - 1)
                    round(type.element, value)
                }
            }

            Intrinsic.All -> (arg(0) as? List<*>)?.all { it as Boolean } ?: arg(0)
            Intrinsic.Any -> (arg(0) as? List<*>)?.any { it as Boolean } ?: arg(0)
            Intrinsic.IsNan -> lanes(type, listOf(arg(0))) { v, _ -> (v[0] as Double).isNaN() }
            Intrinsic.IsInf -> lanes(type, listOf(arg(0))) { v, _ -> (v[0] as Double).isInfinite() }
            Intrinsic.IsFinite -> lanes(type, listOf(arg(0))) { v, _ -> (v[0] as Double).isFinite() }
            Intrinsic.Transpose -> {
                val matrix = arg(0) as List<*>
                val rows = (matrix[0] as List<*>).size
                MutableList(rows) { r -> MutableList(matrix.size) { c -> (matrix[c] as List<*>)[r]!! } }
            }

            Intrinsic.Dfdx, Intrinsic.Dfdy, Intrinsic.Fwidth -> zero(type)
            Intrinsic.ThreadgroupBarrier, Intrinsic.SimdgroupBarrier, Intrinsic.MemoryFence -> null
            Intrinsic.Discard -> {
                discarded = true
                null
            }

            Intrinsic.IsFunctionConstantDefined -> false
            Intrinsic.SimdSum, Intrinsic.SimdMin, Intrinsic.SimdMax, Intrinsic.SimdAnd, Intrinsic.SimdOr, Intrinsic.SimdXor, Intrinsic.SimdProduct,
            Intrinsic.SimdPrefixInclusiveSum, Intrinsic.SimdPrefixInclusiveProduct, Intrinsic.SimdBroadcast, Intrinsic.SimdBroadcastFirst,
            Intrinsic.SimdShuffle, Intrinsic.SimdShuffleUp, Intrinsic.SimdShuffleDown, Intrinsic.SimdShuffleXor, Intrinsic.QuadBroadcast,
            Intrinsic.QuadShuffle, Intrinsic.QuadShuffleXor,
            -> copy(arg(0))

            Intrinsic.SimdPrefixExclusiveSum -> zero(type)
            Intrinsic.SimdPrefixExclusiveProduct -> lanes(type, listOf(arg(0))) { _, t -> if (t is IrFloat) 1.0 else 1L }
            Intrinsic.SimdAll, Intrinsic.SimdAny -> arg(0)
            Intrinsic.SimdIsFirst -> true
            Intrinsic.SimdBallot, Intrinsic.SimdActiveThreadsMask -> 1L
            Intrinsic.TextureSample, Intrinsic.TextureSampleCompare, Intrinsic.TextureGather, Intrinsic.TextureGatherCompare,
            Intrinsic.TextureRead, Intrinsic.TextureCalculateLod,
            -> texel(instruction)

            Intrinsic.TextureWrite -> {
                val handle = arg(0) as ResourceHandle
                val parts = ops.drop(1).map { operand(it) }
                writes["texture ${handle.name}[${handle.element}] ${render(parts.dropLast(1))}"] = copy(parts.last())
                null
            }

            Intrinsic.TextureSize, Intrinsic.TextureLevels, Intrinsic.TextureSamples -> normalize(type.scalar as IrInt, 16)
            Intrinsic.AtomicLoad -> copy(load(arg(0) as Pointer))
            Intrinsic.AtomicStore -> {
                store(arg(0) as Pointer, arg(1))
                null
            }

            Intrinsic.AtomicExchange, Intrinsic.AtomicAdd, Intrinsic.AtomicSub, Intrinsic.AtomicAnd, Intrinsic.AtomicOr, Intrinsic.AtomicXor,
            Intrinsic.AtomicSMin, Intrinsic.AtomicSMax, Intrinsic.AtomicUMin, Intrinsic.AtomicUMax, Intrinsic.AtomicFAdd, Intrinsic.AtomicCompareExchange,
            -> atomic(instruction)

            else -> throw InterpreterLimit("unsupported intrinsic $intrinsic")
        }
    }

    private fun atomic(instruction: Instruction): Any {
        val pointer = operand(instruction.operands[0]) as Pointer
        val old = load(pointer)
        val value = operand(instruction.operands[1])
        val type = instruction.type as IrScalar
        val result: Any = when (instruction.intrinsic) {
            Intrinsic.AtomicExchange -> value
            Intrinsic.AtomicAdd -> normalize(type as IrInt, (old as Long) + (value as Long))
            Intrinsic.AtomicSub -> normalize(type as IrInt, (old as Long) - (value as Long))
            Intrinsic.AtomicAnd -> (old as Long) and (value as Long)
            Intrinsic.AtomicOr -> (old as Long) or (value as Long)
            Intrinsic.AtomicXor -> (old as Long) xor (value as Long)
            Intrinsic.AtomicSMin, Intrinsic.AtomicUMin -> minOf(old as Long, value as Long)
            Intrinsic.AtomicSMax, Intrinsic.AtomicUMax -> maxOf(old as Long, value as Long)
            Intrinsic.AtomicFAdd -> round(type, (old as Double) + (value as Double))
            else -> if (old == operand(instruction.operands[2])) value else old
        }
        store(pointer, result)
        return old
    }

    private fun texel(instruction: Instruction): Any {
        val handle = operand(instruction.operands[0]) as ResourceHandle
        var seed = handle.name.hashCode() % 7 * 0.1 + handle.element * 0.37
        val literals = instruction.literals
        var index = 1
        for (operand in instruction.operands.drop(1)) {
            val value = operand(operand)
            val numbers = numbers(value)
            for (number in numbers) seed += number * (0.31 + 0.17 * index++)
        }
        seed += (literals.getOrElse(1) { 0 }) * 0.21
        val type = instruction.type
        val lanes = listOf(kotlin.math.sin(seed), kotlin.math.cos(seed), kotlin.math.sin(seed * 0.5), kotlin.math.cos(seed * 0.25))
        val scalar = type.scalar
        val converted = lanes.map { if (scalar is IrFloat) round(scalar, it) else normalize(scalar as IrInt, (it * 8).toLong()) }
        if (instruction.intrinsic == Intrinsic.TextureCalculateLod) return converted[0]
        return if (type is IrVector) converted.take(type.count).toMutableList() else converted[0]
    }

    private fun determinant(m: List<List<Double>>): Double {
        if (m.size == 1) return m[0][0]
        if (m.size == 2) return m[0][0] * m[1][1] - m[1][0] * m[0][1]
        var total = 0.0
        for (column in m.indices) {
            val minor = m.indices.filter { it != column }.map { c -> m[c].drop(1) }
            total += (if (column % 2 == 0) 1 else -1) * m[column][0] * determinant(minor)
        }
        return total
    }

    private fun numbers(value: Any): List<Double> = when (value) {
        is Double -> listOf(value)
        is Long -> listOf(value.toDouble())
        is Boolean -> listOf(if (value) 1.0 else 0.0)
        is List<*> -> value.flatMap { numbers(it!!) }
        is ResourceHandle -> listOf(value.element.toDouble())
        else -> emptyList()
    }

    private fun load(pointer: Pointer): Any {
        var current: Any = pointer.cell.value
        for (index in pointer.path) {
            val list = current as? List<*> ?: return zero(IrVoid)
            current = if (index >= 0 && index < list.size) list[index.toInt()]!! else return zeroLike(list.firstOrNull())
        }
        return current
    }

    private fun zeroLike(sample: Any?): Any = when (sample) {
        is Double -> 0.0
        is Long -> 0L
        is Boolean -> false
        is List<*> -> sample.map { zeroLike(it) }.toMutableList()
        else -> 0L
    }

    private fun store(pointer: Pointer, value: Any) {
        if (pointer.path.isEmpty()) {
            pointer.cell.value = value
            return
        }
        setPath(pointer.cell.value, pointer.path, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPath(root: Any, path: List<Long>, value: Any) {
        var current = root as MutableList<Any>
        for (i in 0 until path.size - 1) {
            val index = path[i]
            if (index < 0 || index >= current.size) return
            current = current[index.toInt()] as MutableList<Any>
        }
        val last = path.last()
        if (last >= 0 && last < current.size) current[last.toInt()] = value
    }

    private fun copy(value: Any): Any = if (value is List<*>) value.map { copy(it!!) }.toMutableList() else value

    private fun render(value: Any): String = when (value) {
        is List<*> -> value.joinToString(",", "(", ")") { render(it!!) }
        is Double -> "%.3f".format(value)
        else -> value.toString()
    }

    companion object {
        fun round(type: IrScalar, value: Double): Double = when {
            type !is IrFloat -> value
            type.bits == 16 -> HalfFloat.round(value)
            type.bits == 32 -> value.toFloat().toDouble()
            else -> value
        }

        fun mask(value: Long, bits: Int): Long = if (bits >= 64) value else value and ((1L shl bits) - 1)

        fun signExtend(value: Long, bits: Int): Long = if (bits >= 64) value else (value shl (64 - bits)) shr (64 - bits)

        fun normalize(type: IrInt, value: Long): Long = ConstantScalar.int(type, value).bits

        fun toBits(value: Any, type: IrScalar): List<Pair<Long, Int>> = when (value) {
            is List<*> -> value.flatMap { toBits(it!!, type) }
            is Double -> listOf(
                when (type.bits) {
                    16 -> HalfFloat.fromFloat(value.toFloat()).toLong()
                    32 -> java.lang.Float.floatToRawIntBits(value.toFloat()).toLong() and 0xFFFFFFFFL
                    else -> java.lang.Double.doubleToRawLongBits(value)
                } to type.bits,
            )

            is Long -> listOf(mask(value, type.bits) to type.bits)
            is Boolean -> listOf((if (value) 1L else 0L) to 32)
            else -> throw InterpreterLimit("cannot bitcast $value")
        }

        fun fromBits(type: IrScalar, bits: List<Pair<Long, Int>>): Any {
            var accumulator = 0L
            var shift = 0
            for ((value, width) in bits) {
                accumulator = accumulator or (value shl shift)
                shift += width
            }
            return when (type) {
                is IrFloat -> when (type.bits) {
                    16 -> HalfFloat.toFloat(accumulator.toInt()).toDouble()
                    32 -> java.lang.Float.intBitsToFloat(accumulator.toInt()).toDouble()
                    else -> java.lang.Double.longBitsToDouble(accumulator)
                }

                is IrInt -> normalize(type, accumulator)
                is IrBool -> accumulator != 0L
            }
        }
    }
}
