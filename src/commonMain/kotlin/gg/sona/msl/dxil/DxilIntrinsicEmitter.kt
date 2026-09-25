package gg.sona.msl.dxil

import gg.sona.msl.hir.TextureAtomicOperation
import gg.sona.msl.ir.ImageDim
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.MemoryFlags
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.llvm.LlvmBuilder
import gg.sona.msl.llvm.LlvmConstantFloat
import gg.sona.msl.llvm.LlvmConstantInt
import gg.sona.msl.llvm.LlvmFloatType
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmType
import gg.sona.msl.llvm.LlvmUndef
import gg.sona.msl.llvm.LlvmValue
import gg.sona.msl.llvm.LlvmVoidType

class DxilIntrinsicEmitter(private val emitter: DxilEmitter) {
    private val builder = emitter.builder

    fun emit(instruction: Instruction): List<LlvmValue>? {
        val intrinsic = instruction.intrinsic!!
        DxilNativeIntrinsics.UNARY[intrinsic]?.let { opcode -> return map(instruction) { unary(opcode, it) } }
        DxilNativeIntrinsics.BINARY[intrinsic]?.let { opcode -> return map2(instruction) { a, b -> binary(opcode, a, b) } }
        DxilNativeIntrinsics.SPECIAL_FLOAT[intrinsic]?.let { opcode ->
            return map(instruction) { emitter.callOp("isSpecialFloat", opcode, it.type, LlvmIntType.I1, listOf(it)) }
        }
        return when (intrinsic) {
            Intrinsic.Fwidth -> map(instruction) {
                val dx = unary(DxilOpcode.FAbs, unary(DxilOpcode.DerivCoarseX, it))
                val dy = unary(DxilOpcode.FAbs, unary(DxilOpcode.DerivCoarseY, it))
                builder.binary(LlvmBuilder.BINOP_ADD, dx, dy)
            }

            Intrinsic.Fma -> {
                val a = emitter.components(instruction.operands[0])
                val b = emitter.components(instruction.operands[1])
                val c = emitter.components(instruction.operands[2])
                a.indices.map { emitter.callOp("tertiary", DxilOpcode.FMad, a[it].type, a[it].type, listOf(a[it], b[it], c[it])) }
            }

            Intrinsic.Dot -> {
                val a = emitter.components(instruction.operands[0])
                val b = emitter.components(instruction.operands[1])
                val opcode = when (a.size) {
                    2 -> DxilOpcode.Dot2
                    3 -> DxilOpcode.Dot3
                    else -> DxilOpcode.Dot4
                }
                listOf(emitter.callOp("dot${a.size}", opcode, a[0].type, a[0].type, a + b))
            }

            Intrinsic.Popcount -> {
                val narrow = (instruction.type.scalar as IrInt).bits == 8
                integerBits(instruction) { value ->
                    val operand = if (narrow) builder.binary(LlvmBuilder.BINOP_AND, value, emitter.i32(0xFF)) else value
                    emitter.callOp("unaryBits", DxilOpcode.Countbits, operand.type, LlvmIntType.I32, listOf(operand))
                }
            }
            Intrinsic.ReverseBits -> map(instruction) { value ->
                val bits = (value.type as LlvmIntType).bits
                if (bits == 16) {
                    val wide = builder.cast(LlvmBuilder.CAST_ZEXT, value, LlvmIntType.I32)
                    val reversed = unary(DxilOpcode.Bfrev, wide)
                    builder.cast(LlvmBuilder.CAST_TRUNC, builder.binary(LlvmBuilder.BINOP_LSHR, reversed, emitter.i32(16)), LlvmIntType.I16)
                } else {
                    val scalar = instruction.type.scalar as IrInt
                    val reversed = unary(DxilOpcode.Bfrev, value)
                    if (scalar.bits == 8) builder.binary(LlvmBuilder.BINOP_LSHR, reversed, emitter.i32(24)) else reversed
                }
            }

            Intrinsic.Clz -> bitSearch(instruction, high = true)
            Intrinsic.Ctz -> bitSearch(instruction, high = false)
            Intrinsic.SExtractBits, Intrinsic.UExtractBits -> {
                val opcode = if (intrinsic == Intrinsic.SExtractBits) DxilOpcode.Ibfe else DxilOpcode.Ubfe
                val offset = emitter.scalar(instruction.operands[1])
                val width = emitter.scalar(instruction.operands[2])
                map(instruction) { emitter.callOp("tertiary", opcode, it.type, it.type, listOf(width, offset, it)) }
            }

            Intrinsic.InsertBits -> {
                val base = emitter.components(instruction.operands[0])
                val insert = emitter.components(instruction.operands[1])
                val offset = emitter.scalar(instruction.operands[2])
                val width = emitter.scalar(instruction.operands[3])
                base.indices.map { emitter.callOp("quaternary", DxilOpcode.Bfi, base[it].type, base[it].type, listOf(width, offset, insert[it], base[it])) }
            }

            Intrinsic.SMulHi, Intrinsic.UMulHi -> {
                val signed = intrinsic == Intrinsic.SMulHi
                val extend = if (signed) LlvmBuilder.CAST_SEXT else LlvmBuilder.CAST_ZEXT
                emitter.features.int64 = true
                map2(instruction) { a, b ->
                    val wideA = builder.cast(extend, a, LlvmIntType.I64)
                    val wideB = builder.cast(extend, b, LlvmIntType.I64)
                    val product = builder.binary(LlvmBuilder.BINOP_MUL, wideA, wideB)
                    val high = builder.binary(if (signed) LlvmBuilder.BINOP_ASHR else LlvmBuilder.BINOP_LSHR, product, LlvmConstantInt(LlvmIntType.I64, 32))
                    builder.cast(LlvmBuilder.CAST_TRUNC, high, LlvmIntType.I32)
                }
            }

            Intrinsic.All, Intrinsic.Any -> {
                val components = emitter.components(instruction.operands[0])
                val code = if (intrinsic == Intrinsic.All) LlvmBuilder.BINOP_AND else LlvmBuilder.BINOP_OR
                listOf(components.reduce { a, b -> builder.binary(code, a, b) })
            }

            Intrinsic.Discard -> {
                emitter.callOp("discard", DxilOpcode.Discard, null, LlvmVoidType, listOf(emitter.i1(true)), DxilEmitter.NO_UNWIND)
                null
            }

            Intrinsic.ThreadgroupBarrier, Intrinsic.MemoryFence -> {
                val flags = MemoryFlags(instruction.literals.getOrElse(0) { 0 })
                var mode = if (intrinsic == Intrinsic.ThreadgroupBarrier) 1 else 0
                if (flags.threadgroup || mode == 1 && !flags.device && !flags.texture) mode = mode or 8
                if (flags.device || flags.texture) mode = mode or 2
                if (emitter.stage == ShaderStage.Kernel && mode != 0) emitter.callOp("barrier", DxilOpcode.Barrier, null, LlvmVoidType, listOf(emitter.i32(mode)), DxilEmitter.NO_DUPLICATE)
                null
            }

            Intrinsic.SimdgroupBarrier -> null
            Intrinsic.IsFunctionConstantDefined -> listOf(emitter.scalar(instruction.operands[0]))
            Intrinsic.TextureSample, Intrinsic.TextureSampleCompare -> sample(instruction)
            Intrinsic.TextureGather, Intrinsic.TextureGatherCompare -> gather(instruction)
            Intrinsic.TextureRead -> read(instruction)
            Intrinsic.TextureWrite -> {
                write(instruction)
                null
            }

            Intrinsic.TextureAtomic -> textureAtomic(instruction)

            Intrinsic.TextureSize, Intrinsic.TextureLevels, Intrinsic.TextureSamples -> query(instruction)
            Intrinsic.TextureCalculateLod -> {
                val arguments = TextureOperandsCursor(instruction)
                val handle = emitter.scalar(instruction.operands[0])
                val sampler = emitter.scalar(arguments.next())
                val coordinate = floats(emitter.components(arguments.next()), 3)
                listOf(
                    emitter.callOp(
                        "calculateLOD",
                        DxilOpcode.CalculateLod,
                        LlvmFloatType.Float,
                        LlvmFloatType.Float,
                        listOf(handle, sampler) + coordinate + emitter.i1(arguments.extra == 1),
                        DxilEmitter.READ_ONLY,
                    ),
                )
            }

            Intrinsic.AtomicLoad, Intrinsic.AtomicStore, Intrinsic.AtomicExchange, Intrinsic.AtomicCompareExchange,
            Intrinsic.AtomicAdd, Intrinsic.AtomicSub, Intrinsic.AtomicAnd, Intrinsic.AtomicOr, Intrinsic.AtomicXor,
            Intrinsic.AtomicSMin, Intrinsic.AtomicSMax, Intrinsic.AtomicUMin, Intrinsic.AtomicUMax, Intrinsic.AtomicFAdd,
            -> atomic(instruction)

            Intrinsic.SimdSum, Intrinsic.SimdProduct, Intrinsic.SimdMin, Intrinsic.SimdMax -> {
                val operation = when (intrinsic) {
                    Intrinsic.SimdSum -> 0
                    Intrinsic.SimdProduct -> 1
                    Intrinsic.SimdMin -> 2
                    else -> 3
                }
                wave(instruction) { value, signed -> waveOp("waveActiveOp", DxilOpcode.WaveActiveOp, value, emitter.i8(operation), emitter.i8(if (signed) 0 else 1)) }
            }

            Intrinsic.SimdAnd, Intrinsic.SimdOr, Intrinsic.SimdXor -> {
                val operation = when (intrinsic) {
                    Intrinsic.SimdAnd -> 0
                    Intrinsic.SimdOr -> 1
                    else -> 2
                }
                wave(instruction) { value, _ -> waveOp("waveActiveBit", DxilOpcode.WaveActiveBit, value, emitter.i8(operation)) }
            }

            Intrinsic.SimdPrefixExclusiveSum, Intrinsic.SimdPrefixExclusiveProduct, Intrinsic.SimdPrefixInclusiveSum,
            Intrinsic.SimdPrefixInclusiveProduct,
            -> {
                val product = intrinsic == Intrinsic.SimdPrefixExclusiveProduct || intrinsic == Intrinsic.SimdPrefixInclusiveProduct
                val inclusive = intrinsic == Intrinsic.SimdPrefixInclusiveSum || intrinsic == Intrinsic.SimdPrefixInclusiveProduct
                wave(instruction) { value, signed ->
                    val exclusive = waveOp("wavePrefixOp", DxilOpcode.WavePrefixOp, value, emitter.i8(if (product) 1 else 0), emitter.i8(if (signed) 0 else 1))
                    if (inclusive) builder.binary(if (product) LlvmBuilder.BINOP_MUL else LlvmBuilder.BINOP_ADD, exclusive, value) else exclusive
                }
            }

            Intrinsic.SimdBroadcast, Intrinsic.SimdShuffle -> {
                val lane = laneIndex(instruction.operands[1])
                wave(instruction) { value, _ -> waveOp("waveReadLaneAt", DxilOpcode.WaveReadLaneAt, value, lane) }
            }

            Intrinsic.SimdBroadcastFirst -> wave(instruction) { value, _ -> waveOp("waveReadLaneFirst", DxilOpcode.WaveReadLaneFirst, value) }
            Intrinsic.SimdShuffleUp, Intrinsic.SimdShuffleDown -> {
                val delta = laneIndex(instruction.operands[1])
                val lane = DxilBuiltins(emitter).laneIndex()
                val up = intrinsic == Intrinsic.SimdShuffleUp
                val source = builder.binary(if (up) LlvmBuilder.BINOP_SUB else LlvmBuilder.BINOP_ADD, lane, delta)
                val valid = if (up) {
                    builder.compare(LlvmBuilder.ICMP_UGE, lane, delta)
                } else {
                    builder.compare(LlvmBuilder.ICMP_ULT, source, DxilBuiltins(emitter).laneCount())
                }
                wave(instruction) { value, _ ->
                    val shuffled = waveOp("waveReadLaneAt", DxilOpcode.WaveReadLaneAt, value, source)
                    builder.select(valid, shuffled, value)
                }
            }

            Intrinsic.SimdShuffleXor -> {
                val mask = laneIndex(instruction.operands[1])
                val source = builder.binary(LlvmBuilder.BINOP_XOR, DxilBuiltins(emitter).laneIndex(), mask)
                wave(instruction) { value, _ -> waveOp("waveReadLaneAt", DxilOpcode.WaveReadLaneAt, value, source) }
            }

            Intrinsic.QuadBroadcast -> {
                val lane = laneIndex(instruction.operands[1])
                wave(instruction) { value, _ -> waveOp("quadReadLaneAt", DxilOpcode.QuadReadLaneAt, value, lane) }
            }

            Intrinsic.QuadShuffle -> {
                val lane = laneIndex(instruction.operands[1])
                val base = builder.binary(LlvmBuilder.BINOP_AND, DxilBuiltins(emitter).laneIndex(), emitter.i32(3.inv()))
                val source = builder.binary(LlvmBuilder.BINOP_OR, base, builder.binary(LlvmBuilder.BINOP_AND, lane, emitter.i32(3)))
                wave(instruction) { value, _ -> waveOp("waveReadLaneAt", DxilOpcode.WaveReadLaneAt, value, source) }
            }

            Intrinsic.QuadShuffleXor -> {
                val mask = laneIndex(instruction.operands[1])
                val constant = (mask as? LlvmConstantInt)?.value?.toInt()?.and(3)
                if (constant != null && constant != 0) {
                    wave(instruction) { value, _ -> waveOp("quadOp", DxilOpcode.QuadOp, value, emitter.i8(constant - 1)) }
                } else {
                    val source = builder.binary(LlvmBuilder.BINOP_XOR, DxilBuiltins(emitter).laneIndex(), builder.binary(LlvmBuilder.BINOP_AND, mask, emitter.i32(3)))
                    wave(instruction) { value, _ -> waveOp("waveReadLaneAt", DxilOpcode.WaveReadLaneAt, value, source) }
                }
            }

            Intrinsic.SimdBallot, Intrinsic.SimdActiveThreadsMask -> {
                emitter.features.waveOps = true
                val predicate = if (intrinsic == Intrinsic.SimdBallot) emitter.scalar(instruction.operands[0]) else emitter.i1(true)
                val ballot = emitter.callOp("waveActiveBallot", DxilOpcode.WaveActiveBallot, null, emitter.types.fourI32, listOf(predicate), DxilEmitter.NO_UNWIND)
                val low = builder.cast(LlvmBuilder.CAST_ZEXT, builder.extractValue(ballot, 0), LlvmIntType.I64)
                val high = builder.cast(LlvmBuilder.CAST_ZEXT, builder.extractValue(ballot, 1), LlvmIntType.I64)
                emitter.features.int64 = true
                listOf(builder.binary(LlvmBuilder.BINOP_OR, low, builder.binary(LlvmBuilder.BINOP_SHL, high, LlvmConstantInt(LlvmIntType.I64, 32))))
            }

            Intrinsic.SimdAll, Intrinsic.SimdAny -> {
                emitter.features.waveOps = true
                val opcode = if (intrinsic == Intrinsic.SimdAll) DxilOpcode.WaveAllTrue else DxilOpcode.WaveAnyTrue
                val name = if (intrinsic == Intrinsic.SimdAll) "waveAllTrue" else "waveAnyTrue"
                listOf(emitter.callOp(name, opcode, null, LlvmIntType.I1, listOf(emitter.scalar(instruction.operands[0])), DxilEmitter.NO_UNWIND))
            }

            Intrinsic.SimdIsFirst -> {
                emitter.features.waveOps = true
                listOf(emitter.callOp("waveIsFirstLane", DxilOpcode.WaveIsFirstLane, null, LlvmIntType.I1, emptyList(), DxilEmitter.NO_UNWIND))
            }

            else -> {
                emitter.error("intrinsic ${intrinsic.name} is not supported by the DXIL backend")
                emitter.scalars(instruction.type).map { LlvmUndef(emitter.scalarType(it)) }
            }
        }
    }

    private inline fun map(instruction: Instruction, operation: (LlvmValue) -> LlvmValue): List<LlvmValue> =
        emitter.components(instruction.operands[0]).map(operation)

    private inline fun map2(instruction: Instruction, operation: (LlvmValue, LlvmValue) -> LlvmValue): List<LlvmValue> {
        val a = emitter.components(instruction.operands[0])
        val b = emitter.components(instruction.operands[1])
        return a.indices.map { operation(a[it], b.getOrElse(it) { b[0] }) }
    }

    private fun unary(opcode: Int, value: LlvmValue): LlvmValue {
        return emitter.callOp("unary", opcode, value.type, value.type, listOf(value))
    }

    private fun binary(opcode: Int, a: LlvmValue, b: LlvmValue): LlvmValue = emitter.callOp("binary", opcode, a.type, a.type, listOf(a, b))

    private inline fun integerBits(instruction: Instruction, operation: (LlvmValue) -> LlvmValue): List<LlvmValue> {
        val resultType = instruction.type.scalar
        return map(instruction) { value ->
            val result = operation(value)
            resize(result, resultType)
        }
    }

    private fun resize(value: LlvmValue, target: IrScalar): LlvmValue {
        val type = emitter.scalarType(target) as LlvmIntType
        val bits = (value.type as LlvmIntType).bits
        return when {
            bits == type.bits -> value
            bits > type.bits -> builder.cast(LlvmBuilder.CAST_TRUNC, value, type)
            else -> builder.cast(LlvmBuilder.CAST_ZEXT, value, type)
        }
    }

    private fun bitSearch(instruction: Instruction, high: Boolean): List<LlvmValue> {
        val scalar = instruction.operands[0].type.scalar as IrInt
        val bits = scalar.bits
        return map(instruction) { value ->
            var operand = value
            if (bits == 8) operand = builder.binary(LlvmBuilder.BINOP_AND, operand, emitter.i32(0xFF))
            if (bits == 16) operand = builder.cast(LlvmBuilder.CAST_ZEXT, operand, LlvmIntType.I32)
            val opcode = if (high) DxilOpcode.FirstbitHi else DxilOpcode.FirstbitLo
            val found = emitter.callOp("unaryBits", opcode, operand.type, LlvmIntType.I32, listOf(operand))
            val zero = builder.compare(LlvmBuilder.ICMP_EQ, operand, LlvmConstantInt(operand.type as LlvmIntType, 0))
            val adjusted = if (high && bits < 32) builder.binary(LlvmBuilder.BINOP_SUB, found, emitter.i32(32 - bits)) else found
            val result = builder.select(zero, emitter.i32(bits), adjusted)
            resize(result, instruction.type.scalar)
        }
    }

    private fun laneIndex(value: Value): LlvmValue = emitter.index32(emitter.scalar(value)).let {
        if (it.type == LlvmIntType.I32) it else builder.cast(LlvmBuilder.CAST_ZEXT, it, LlvmIntType.I32)
    }

    private inline fun wave(instruction: Instruction, operation: (LlvmValue, Boolean) -> LlvmValue): List<LlvmValue> {
        emitter.features.waveOps = true
        val scalar = instruction.type.scalar
        val signed = scalar !is IrInt || scalar.signed
        return map(instruction) { operation(it, signed) }
    }

    private fun waveOp(name: String, opcode: Int, value: LlvmValue, vararg extra: LlvmValue): LlvmValue =
        emitter.callOp(name, opcode, value.type, value.type, listOf(value) + extra, DxilEmitter.NO_UNWIND)

    private fun floats(values: List<LlvmValue>, count: Int): List<LlvmValue> =
        List(count) { values.getOrElse(it) { LlvmUndef(LlvmFloatType.Float) } }.map {
            if (it.type == LlvmFloatType.Half) builder.cast(LlvmBuilder.CAST_FPEXT, it, LlvmFloatType.Float) else it
        }

    private fun ints(values: List<LlvmValue>, count: Int): List<LlvmValue> =
        List(count) { values.getOrElse(it) { LlvmUndef(LlvmIntType.I32) } }.map { emitter.index32(it) }

    private fun coordinates(image: IrImage, coordinate: List<LlvmValue>, arrayIndex: LlvmValue?, integer: Boolean): List<LlvmValue> {
        if (arrayIndex == null) return coordinate
        val layer = if (integer) arrayIndex else builder.cast(LlvmBuilder.CAST_UITOFP, arrayIndex, LlvmFloatType.Float)
        return coordinate + layer
    }

    private fun offsets(image: IrImage, offset: Value?): List<LlvmValue> {
        val dimensions = when (image.dim) {
            ImageDim.Dim1D, ImageDim.Buffer -> 1
            ImageDim.Dim2D -> 2
            ImageDim.Dim3D -> 3
            ImageDim.Cube -> 0
        }
        val values = offset?.let { emitter.components(it) } ?: List(dimensions) { emitter.i32(0) }
        return List(3) { if (it < dimensions) values.getOrElse(it) { emitter.i32(0) } else LlvmUndef(LlvmIntType.I32) }
    }

    private fun resultComponents(result: LlvmValue, image: IrImage, target: IrType, count: Int): List<LlvmValue> {
        val components = List(count) { builder.extractValue(result, it) }
        val scalar = target.scalar
        val adapted = components.map { adapt(it, scalar) }
        return if (target is IrScalar) listOf(adapted[0]) else adapted
    }

    private fun adapt(value: LlvmValue, scalar: IrScalar): LlvmValue {
        val type = emitter.scalarType(scalar)
        if (value.type == type) return value
        return when {
            type is LlvmFloatType && value.type is LlvmFloatType -> builder.cast(LlvmBuilder.CAST_FPTRUNC, value, type)
            type is LlvmIntType && value.type is LlvmIntType -> builder.cast(LlvmBuilder.CAST_TRUNC, value, type)
            else -> value
        }
    }

    private fun texelType(image: IrImage): LlvmType = when (image.sampled) {
        gg.sona.msl.ir.SampledKind.Float, gg.sona.msl.ir.SampledKind.Half -> LlvmFloatType.Float
        else -> LlvmIntType.I32
    }

    private fun sample(instruction: Instruction): List<LlvmValue> {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val sampler = emitter.scalar(arguments.next())
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { emitter.index32(emitter.scalar(it)) }
        val bias = arguments.take(TextureOperands.Bias)
        val lod = arguments.take(TextureOperands.Lod)
        val gradient = if (TextureOperands.Gradient in arguments.mask) arguments.next() to arguments.next() else null
        val minLod = arguments.take(TextureOperands.MinLod)
        val offset = arguments.take(TextureOperands.Offset)
        val compare = arguments.take(TextureOperands.Compare)
        val coordinate = floats(coordinates(image, emitter.components(coordinateValue), arrayIndex, false), 4)
        val offsets = offsets(image, offset)
        val type = texelType(image)
        val clamp = minLod?.let { floats(listOf(emitter.scalar(it)), 1)[0] } ?: LlvmUndef(LlvmFloatType.Float)
        val implicitAllowed = emitter.stage == ShaderStage.Fragment
        val base = listOf(handle, sampler) + coordinate + offsets
        val result = when {
            compare != null -> {
                val reference = floats(listOf(emitter.scalar(compare)), 1)[0]
                if (lod != null || !implicitAllowed) {
                    val level = lod?.let { emitter.scalar(it) }
                    if (level != null && !(level is LlvmConstantFloat && level.bits == 0L)) {
                        emitter.error("sample_compare with a non-zero level requires shader model 6.7")
                    }
                    emitter.callOp("sampleCmpLevelZero", DxilOpcode.SampleCmpLevelZero, LlvmFloatType.Float, emitter.types.resRet(LlvmFloatType.Float), base + reference, DxilEmitter.READ_ONLY)
                } else {
                    emitter.callOp("sampleCmp", DxilOpcode.SampleCmp, LlvmFloatType.Float, emitter.types.resRet(LlvmFloatType.Float), base + reference + clamp, DxilEmitter.READ_ONLY)
                }
            }

            gradient != null -> {
                val dx = floats(emitter.components(gradient.first), 3)
                val dy = floats(emitter.components(gradient.second), 3)
                emitter.callOp("sampleGrad", DxilOpcode.SampleGrad, type, emitter.types.resRet(type), base + dx + dy + clamp, DxilEmitter.READ_ONLY)
            }

            lod != null || !implicitAllowed -> {
                val level = lod?.let { floats(listOf(emitter.scalar(it)), 1)[0] } ?: emitter.f32(0f)
                emitter.callOp("sampleLevel", DxilOpcode.SampleLevel, type, emitter.types.resRet(type), base + level, DxilEmitter.READ_ONLY)
            }

            bias != null -> {
                val amount = floats(listOf(emitter.scalar(bias)), 1)[0]
                emitter.callOp("sampleBias", DxilOpcode.SampleBias, type, emitter.types.resRet(type), base + amount + clamp, DxilEmitter.READ_ONLY)
            }

            else -> emitter.callOp("sample", DxilOpcode.Sample, type, emitter.types.resRet(type), base + clamp, DxilEmitter.READ_ONLY)
        }
        return resultComponents(result, image, instruction.type, 4)
    }

    private fun gather(instruction: Instruction): List<LlvmValue> {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val sampler = emitter.scalar(arguments.next())
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { emitter.index32(emitter.scalar(it)) }
        val offset = arguments.take(TextureOperands.Offset)
        val compare = arguments.take(TextureOperands.Compare)
        val coordinate = floats(coordinates(image, emitter.components(coordinateValue), arrayIndex, false), 4)
        val offsets = offsets(image, offset).take(2)
        val type = texelType(image)
        val channel = emitter.i32(arguments.extra)
        val result = if (compare != null) {
            val reference = floats(listOf(emitter.scalar(compare)), 1)[0]
            emitter.callOp("textureGatherCmp", DxilOpcode.TextureGatherCmp, type, emitter.types.resRet(type), listOf(handle, sampler) + coordinate + offsets + channel + reference, DxilEmitter.READ_ONLY)
        } else {
            emitter.callOp("textureGather", DxilOpcode.TextureGather, type, emitter.types.resRet(type), listOf(handle, sampler) + coordinate + offsets + channel, DxilEmitter.READ_ONLY)
        }
        return resultComponents(result, image, instruction.type, 4)
    }

    private fun read(instruction: Instruction): List<LlvmValue> {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { emitter.index32(emitter.scalar(it)) }
        val lod = arguments.take(TextureOperands.Lod)
        val sample = arguments.take(TextureOperands.SampleIndex)
        val type = texelType(image)
        if (image.access.isStorage && image.access != gg.sona.msl.ir.ImageAccess.Write) emitter.features.typedUavLoads = true
        if (image.dim == ImageDim.Buffer) {
            val index = emitter.index32(emitter.scalar(coordinateValue))
            val result = emitter.callOp("bufferLoad", DxilOpcode.BufferLoad, type, emitter.types.resRet(type), listOf(handle, index, LlvmUndef(LlvmIntType.I32)), DxilEmitter.READ_ONLY)
            return resultComponents(result, image, instruction.type, 4)
        }
        val coordinate = ints(coordinates(image, emitter.components(coordinateValue), arrayIndex, true), 3)
        val mip = when {
            sample != null -> emitter.index32(emitter.scalar(sample))
            image.access.isStorage -> LlvmUndef(LlvmIntType.I32)
            lod != null -> emitter.index32(emitter.scalar(lod))
            image.multisampled -> emitter.i32(0)
            else -> emitter.i32(0)
        }
        val offsets = List(3) { LlvmUndef(LlvmIntType.I32) }
        val result = emitter.callOp("textureLoad", DxilOpcode.TextureLoad, type, emitter.types.resRet(type), listOf(handle, mip) + coordinate + offsets, DxilEmitter.READ_ONLY)
        return resultComponents(result, image, instruction.type, 4)
    }

    private fun write(instruction: Instruction) {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { emitter.index32(emitter.scalar(it)) }
        arguments.take(TextureOperands.Lod)
        val texel = arguments.next()
        val type = texelType(image)
        val values = emitter.components(texel).map { value ->
            when {
                value.type == type -> value
                type is LlvmFloatType -> builder.cast(LlvmBuilder.CAST_FPEXT, value, type)
                else -> if ((value.type as LlvmIntType).bits < 32) {
                    builder.cast(if (image.sampled == gg.sona.msl.ir.SampledKind.SShort) LlvmBuilder.CAST_SEXT else LlvmBuilder.CAST_ZEXT, value, type)
                } else {
                    value
                }
            }
        }
        if (image.dim == ImageDim.Buffer) {
            val index = emitter.index32(emitter.scalar(coordinateValue))
            emitter.callOp("bufferStore", DxilOpcode.BufferStore, type, LlvmVoidType, listOf(handle, index, LlvmUndef(LlvmIntType.I32)) + values + emitter.i8(15), DxilEmitter.NO_UNWIND)
            return
        }
        val coordinate = ints(coordinates(image, emitter.components(coordinateValue), arrayIndex, true), 3)
        emitter.callOp("textureStore", DxilOpcode.TextureStore, type, LlvmVoidType, listOf(handle) + coordinate + values + emitter.i8(15), DxilEmitter.NO_UNWIND)
    }

    private fun textureAtomic(instruction: Instruction): List<LlvmValue>? {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { emitter.index32(emitter.scalar(it)) }
        val coordinate = ints(coordinates(image, emitter.components(coordinateValue), arrayIndex, true), 3)
        val signed = image.sampled == gg.sona.msl.ir.SampledKind.SInt
        val operation = TextureAtomicOperation.entries[instruction.literals[1]]
        fun binary(code: Int, value: LlvmValue): LlvmValue = emitter.callOp(
            "atomicBinOp",
            DxilOpcode.AtomicBinOp,
            LlvmIntType.I32,
            LlvmIntType.I32,
            listOf(handle, emitter.i32(code)) + coordinate + value,
            DxilEmitter.NO_UNWIND,
        )
        fun value(): LlvmValue = emitter.scalar(arguments.next())
        return when (operation) {
            TextureAtomicOperation.Load -> listOf(binary(DxilBufferAccess.ATOMIC_OR, emitter.i32(0)))
            TextureAtomicOperation.Store -> {
                binary(DxilBufferAccess.ATOMIC_EXCHANGE, value())
                null
            }

            TextureAtomicOperation.CompareExchange -> {
                val desired = value()
                val comparand = value()
                listOf(
                    emitter.callOp(
                        "atomicCompareExchange",
                        DxilOpcode.AtomicCompareExchange,
                        LlvmIntType.I32,
                        LlvmIntType.I32,
                        listOf(handle) + coordinate + comparand + desired,
                        DxilEmitter.NO_UNWIND,
                    ),
                )
            }

            TextureAtomicOperation.Sub -> listOf(binary(DxilBufferAccess.ATOMIC_ADD, builder.binary(LlvmBuilder.BINOP_SUB, emitter.i32(0), value())))
            else -> {
                val code = when (operation) {
                    TextureAtomicOperation.Exchange -> DxilBufferAccess.ATOMIC_EXCHANGE
                    TextureAtomicOperation.Add -> DxilBufferAccess.ATOMIC_ADD
                    TextureAtomicOperation.And -> DxilBufferAccess.ATOMIC_AND
                    TextureAtomicOperation.Or -> DxilBufferAccess.ATOMIC_OR
                    TextureAtomicOperation.Xor -> DxilBufferAccess.ATOMIC_XOR
                    TextureAtomicOperation.Min -> if (signed) DxilBufferAccess.ATOMIC_IMIN else DxilBufferAccess.ATOMIC_UMIN
                    else -> if (signed) DxilBufferAccess.ATOMIC_IMAX else DxilBufferAccess.ATOMIC_UMAX
                }
                listOf(binary(code, value()))
            }
        }
    }

    private fun query(instruction: Instruction): List<LlvmValue> {
        val image = instruction.operands[0].type as IrImage
        val arguments = TextureOperandsCursor(instruction)
        val handle = emitter.scalar(instruction.operands[0])
        val lod = if (instruction.intrinsic == Intrinsic.TextureSize) arguments.take(TextureOperands.Lod) else null
        val mip = if (image.access.isStorage || image.multisampled || image.dim == ImageDim.Buffer) {
            LlvmUndef(LlvmIntType.I32)
        } else {
            lod?.let { emitter.index32(emitter.scalar(it)) } ?: emitter.i32(0)
        }
        val dimensions = emitter.callOp("getDimensions", DxilOpcode.GetDimensions, null, emitter.types.dimensions, listOf(handle, mip), DxilEmitter.READ_ONLY)
        val component = when (instruction.intrinsic) {
            Intrinsic.TextureLevels, Intrinsic.TextureSamples -> 3
            else -> when (arguments.extra) {
                3 -> if (image.dim == ImageDim.Dim1D) 1 else 2
                else -> arguments.extra
            }
        }
        val value = builder.extractValue(dimensions, component)
        return listOf(resize(value, instruction.type.scalar))
    }

    private fun atomic(instruction: Instruction): List<LlvmValue>? {
        val intrinsic = instruction.intrinsic!!
        val pointer = emitter.pointer(instruction.operands[0])
        val element = instruction.operands[0].type.let { (it as gg.sona.msl.ir.IrPointer).pointee.scalar }
        val value = instruction.operands.getOrNull(1)?.let { emitter.scalar(it) }
        return when (pointer) {
            is BufferPointer -> bufferAtomic(intrinsic, pointer, element, value, instruction)
            is LocalPointer -> localAtomic(intrinsic, pointer, element, value, instruction)
            else -> {
                emitter.error("atomics are only supported on buffers and threadgroup memory")
                null
            }
        }
    }

    private fun bufferAtomic(intrinsic: Intrinsic, pointer: BufferPointer, element: IrScalar, value: LlvmValue?, instruction: Instruction): List<LlvmValue>? {
        val access = DxilBufferAccess(emitter)
        val resource = pointer.resource
        val offset = pointer.offset
        fun asInt(v: LlvmValue): LlvmValue = if (v.type == LlvmFloatType.Float) builder.cast(LlvmBuilder.CAST_BITCAST, v, LlvmIntType.I32) else v
        fun fromInt(v: LlvmValue): LlvmValue = if (element is IrFloat) builder.cast(LlvmBuilder.CAST_BITCAST, v, LlvmFloatType.Float) else v
        return when (intrinsic) {
            Intrinsic.AtomicLoad -> listOf(fromInt(access.atomic(resource, offset, DxilBufferAccess.ATOMIC_OR, emitter.i32(0))))
            Intrinsic.AtomicStore -> {
                access.atomic(resource, offset, DxilBufferAccess.ATOMIC_EXCHANGE, asInt(value!!))
                null
            }

            Intrinsic.AtomicCompareExchange -> {
                val comparand = asInt(emitter.scalar(instruction.operands[2]))
                listOf(fromInt(access.compareExchange(resource, offset, comparand, asInt(value!!))))
            }

            Intrinsic.AtomicFAdd -> listOf(floatAdd({ access.atomic(resource, offset, DxilBufferAccess.ATOMIC_OR, emitter.i32(0)) }, value!!) { expected, desired ->
                access.compareExchange(resource, offset, expected, desired)
            })

            else -> {
                val operation = when (intrinsic) {
                    Intrinsic.AtomicExchange -> DxilBufferAccess.ATOMIC_EXCHANGE
                    Intrinsic.AtomicAdd, Intrinsic.AtomicSub -> DxilBufferAccess.ATOMIC_ADD
                    Intrinsic.AtomicAnd -> DxilBufferAccess.ATOMIC_AND
                    Intrinsic.AtomicOr -> DxilBufferAccess.ATOMIC_OR
                    Intrinsic.AtomicXor -> DxilBufferAccess.ATOMIC_XOR
                    Intrinsic.AtomicSMin -> DxilBufferAccess.ATOMIC_IMIN
                    Intrinsic.AtomicSMax -> DxilBufferAccess.ATOMIC_IMAX
                    Intrinsic.AtomicUMin -> DxilBufferAccess.ATOMIC_UMIN
                    else -> DxilBufferAccess.ATOMIC_UMAX
                }
                val operand = if (intrinsic == Intrinsic.AtomicSub) builder.binary(LlvmBuilder.BINOP_SUB, emitter.i32(0), value!!) else asInt(value!!)
                listOf(fromInt(access.atomic(resource, offset, operation, operand)))
            }
        }
    }

    private fun localAtomic(intrinsic: Intrinsic, pointer: LocalPointer, element: IrScalar, value: LlvmValue?, instruction: Instruction): List<LlvmValue>? {
        val leaf = pointer.node.leaf ?: run {
            emitter.error("atomic operation on a non-scalar location")
            return null
        }
        val address = emitter.leafPointer(leaf, pointer.indices)
        fun asInt(v: LlvmValue): LlvmValue = if (v.type == LlvmFloatType.Float) builder.cast(LlvmBuilder.CAST_BITCAST, v, LlvmIntType.I32) else v
        fun fromInt(v: LlvmValue): LlvmValue = if (element is IrFloat) builder.cast(LlvmBuilder.CAST_BITCAST, v, LlvmFloatType.Float) else v
        if (leaf.arrayType.element == LlvmFloatType.Float) {
            emitter.error("floating-point threadgroup atomics are not supported")
            return null
        }
        return when (intrinsic) {
            Intrinsic.AtomicLoad -> listOf(builder.atomicRmw(LlvmBuilder.RMW_OR, address, emitter.i32(0)))
            Intrinsic.AtomicStore -> {
                builder.atomicRmw(LlvmBuilder.RMW_XCHG, address, asInt(value!!))
                null
            }

            Intrinsic.AtomicCompareExchange -> {
                val comparand = asInt(emitter.scalar(instruction.operands[2]))
                listOf(fromInt(builder.extractValue(builder.compareExchange(address, comparand, asInt(value!!)), 0)))
            }

            else -> {
                val operation = when (intrinsic) {
                    Intrinsic.AtomicExchange -> LlvmBuilder.RMW_XCHG
                    Intrinsic.AtomicAdd -> LlvmBuilder.RMW_ADD
                    Intrinsic.AtomicSub -> LlvmBuilder.RMW_SUB
                    Intrinsic.AtomicAnd -> LlvmBuilder.RMW_AND
                    Intrinsic.AtomicOr -> LlvmBuilder.RMW_OR
                    Intrinsic.AtomicXor -> LlvmBuilder.RMW_XOR
                    Intrinsic.AtomicSMin -> LlvmBuilder.RMW_MIN
                    Intrinsic.AtomicSMax -> LlvmBuilder.RMW_MAX
                    Intrinsic.AtomicUMin -> LlvmBuilder.RMW_UMIN
                    Intrinsic.AtomicUMax -> LlvmBuilder.RMW_UMAX
                    else -> {
                        emitter.error("unsupported threadgroup atomic ${intrinsic.name}")
                        return null
                    }
                }
                listOf(fromInt(builder.atomicRmw(operation, address, asInt(value!!))))
            }
        }
    }

    private inline fun floatAdd(load: () -> LlvmValue, value: LlvmValue, exchange: (LlvmValue, LlvmValue) -> LlvmValue): LlvmValue {
        val initial = load()
        val start = builder.block
        val loop = start.function.block()
        val done = start.function.block()
        builder.branch(loop)
        builder.position(loop)
        val current = builder.phi(LlvmIntType.I32)
        val sum = builder.binary(LlvmBuilder.BINOP_ADD, builder.cast(LlvmBuilder.CAST_BITCAST, current, LlvmFloatType.Float), value)
        val original = exchange(current, builder.cast(LlvmBuilder.CAST_BITCAST, sum, LlvmIntType.I32))
        val succeeded = builder.compare(LlvmBuilder.ICMP_EQ, original, current)
        current.operands.add(initial)
        current.targets.add(start)
        current.operands.add(original)
        current.targets.add(loop)
        builder.conditionalBranch(succeeded, done, loop)
        builder.position(done)
        return builder.cast(LlvmBuilder.CAST_BITCAST, current, LlvmFloatType.Float)
    }
}
