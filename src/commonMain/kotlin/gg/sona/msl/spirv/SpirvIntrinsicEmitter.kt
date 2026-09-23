package gg.sona.msl.spirv

import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.ImageDim
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.MemoryFlags
import gg.sona.msl.ir.SampledKind
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.util.IntList

class SpirvIntrinsicEmitter(private val e: SpirvEmitter) {
    private fun type(type: IrType) = e.type(type)

    private fun operand(instruction: Instruction, index: Int) = e.operand(instruction.operands[index])

    fun emit(instruction: Instruction): Int? {
        val intrinsic = instruction.intrinsic!!
        val resultType = instruction.type
        val args by lazy { instruction.operands.map { e.operand(it) } }
        GLSL[intrinsic]?.let { return e.extended(it, type(resultType), *args.toIntArray()) }
        val scalar by lazy { instruction.operands.firstOrNull()?.type?.scalar }
        return when (intrinsic) {
            Intrinsic.FMin -> e.extended(Spv.GlslFMin, type(resultType), *args.toIntArray())
            Intrinsic.Dot -> e.instruction(Spv.OpDot, type(resultType), args[0], args[1])
            Intrinsic.Transpose -> e.instruction(Spv.OpTranspose, type(resultType), args[0])
            Intrinsic.IsNan -> e.instruction(Spv.OpIsNan, type(resultType), args[0])
            Intrinsic.IsInf -> e.instruction(Spv.OpIsInf, type(resultType), args[0])
            Intrinsic.All, Intrinsic.Any -> if (instruction.operands[0].type is IrVector) {
                e.instruction(if (intrinsic == Intrinsic.All) Spv.OpAll else Spv.OpAny, type(resultType), args[0])
            } else {
                args[0]
            }

            Intrinsic.Dfdx, Intrinsic.Dfdy, Intrinsic.Fwidth -> {
                val opcode = when (intrinsic) {
                    Intrinsic.Dfdx -> Spv.OpDPdx
                    Intrinsic.Dfdy -> Spv.OpDPdy
                    else -> Spv.OpFwidth
                }
                e.instruction(opcode, type(resultType), args[0])
            }

            Intrinsic.Popcount -> widened(instruction, args[0]) { value, wide -> e.instruction(Spv.OpBitCount, type(wide), value) }
            Intrinsic.ReverseBits -> {
                val bits = scalar!!.bits
                widened(instruction, args[0]) { value, wide ->
                    val reversed = e.instruction(Spv.OpBitReverse, type(wide), value)
                    if (bits < 32) e.instruction(Spv.OpShiftRightLogical, type(wide), reversed, splat(wide, 32 - bits)) else reversed
                }
            }

            Intrinsic.Clz -> {
                val source = instruction.operands[0].type
                val bits = source.scalar.bits
                if (bits == 64) {
                    e.error("64-bit clz is not supported")
                    args[0]
                } else {
                    val unsignedSource = withScalar(source, IrInt.of(bits, false))
                    var value = if (source == unsignedSource) args[0] else e.instruction(Spv.OpBitcast, type(unsignedSource), args[0])
                    val wide = withScalar(source, IrInt.U32)
                    if (bits < 32) value = e.instruction(Spv.OpUConvert, type(wide), value)
                    val signedWide = withScalar(source, IrInt.I32)
                    val msb = e.extended(Spv.GlslFindUMsb, type(signedWide), value)
                    val count = e.instruction(Spv.OpISub, type(signedWide), splat(signedWide, bits - 1), msb)
                    val resultWide = withScalar(resultType, IrInt.of(32, (resultType.scalar as IrInt).signed))
                    val converted = if (resultWide == signedWide) count else e.instruction(Spv.OpBitcast, type(resultWide), count)
                    if (bits < 32) e.instruction(Spv.OpSConvert, type(resultType), converted) else converted
                }
            }

            Intrinsic.Ctz -> {
                val bits = scalar!!.bits
                widened(instruction, args[0]) { value, wide ->
                    val signedWide = withScalar(wide, IrInt.I32)
                    val lsb = e.instruction(Spv.OpBitcast, type(wide), e.extended(Spv.GlslFindILsb, type(signedWide), value))
                    val zero = e.instruction(Spv.OpIEqual, type(withScalar(wide, IrBool)), value, splat(wide, 0))
                    e.instruction(Spv.OpSelect, type(wide), zero, splat(wide, bits), lsb)
                }
            }

            Intrinsic.SExtractBits, Intrinsic.UExtractBits -> widened(instruction, args[0]) { value, wide ->
                val opcode = if (intrinsic == Intrinsic.SExtractBits) Spv.OpBitFieldSExtract else Spv.OpBitFieldUExtract
                e.instruction(opcode, type(wide), value, args[1], args[2])
            }

            Intrinsic.InsertBits -> {
                val base = instruction.operands[0].type
                if (base.scalar.bits == 32) {
                    e.instruction(Spv.OpBitFieldInsert, type(resultType), args[0], args[1], args[2], args[3])
                } else {
                    val wide = withScalar(base, IrInt.of(32, (base.scalar as IrInt).signed))
                    val convert = if ((base.scalar as IrInt).signed) Spv.OpSConvert else Spv.OpUConvert
                    val a = e.instruction(convert, type(wide), args[0])
                    val b = e.instruction(convert, type(wide), args[1])
                    val inserted = e.instruction(Spv.OpBitFieldInsert, type(wide), a, b, args[2], args[3])
                    e.instruction(convert, type(resultType), inserted)
                }
            }

            Intrinsic.SMulHi, Intrinsic.UMulHi -> {
                val struct = e.structOf(resultType, resultType)
                val opcode = if (intrinsic == Intrinsic.SMulHi) Spv.OpSMulExtended else Spv.OpUMulExtended
                val product = e.instruction(opcode, struct, args[0], args[1])
                e.instruction(Spv.OpCompositeExtract, type(resultType), product, 1)
            }

            Intrinsic.FrexpMantissa, Intrinsic.FrexpExponent -> {
                val input = instruction.operands[0].type
                val exponentType = withScalar(input, IrInt.I32)
                val struct = e.structOf(input, exponentType)
                val pair = e.extended(Spv.GlslFrexpStruct, struct, args[0])
                if (intrinsic == Intrinsic.FrexpMantissa) {
                    e.instruction(Spv.OpCompositeExtract, type(resultType), pair, 0)
                } else {
                    val exponent = e.instruction(Spv.OpCompositeExtract, type(exponentType), pair, 1)
                    if (exponentType == resultType) exponent else e.instruction(Spv.OpBitcast, type(resultType), exponent)
                }
            }

            Intrinsic.Discard -> {
                if (e.version.atLeast(SpirvVersion.V1_6)) {
                    e.capability(Spv.CapabilityDemoteToHelperInvocation)
                } else {
                    e.extension("SPV_EXT_demote_to_helper_invocation")
                    e.capability(Spv.CapabilityDemoteToHelperInvocation)
                }
                e.statement(Spv.OpDemoteToHelperInvocation)
                null
            }

            Intrinsic.ThreadgroupBarrier, Intrinsic.SimdgroupBarrier, Intrinsic.MemoryFence -> {
                barrier(intrinsic, MemoryFlags(instruction.literals.getOrElse(0) { 0 }))
                null
            }

            Intrinsic.TextureSample, Intrinsic.TextureSampleCompare -> sample(instruction)
            Intrinsic.TextureGather, Intrinsic.TextureGatherCompare -> gather(instruction)
            Intrinsic.TextureRead -> read(instruction)
            Intrinsic.TextureWrite -> {
                write(instruction)
                null
            }

            Intrinsic.TextureSize -> size(instruction)
            Intrinsic.TextureLevels, Intrinsic.TextureSamples -> {
                e.capability(Spv.CapabilityImageQuery)
                val opcode = if (intrinsic == Intrinsic.TextureLevels) Spv.OpImageQueryLevels else Spv.OpImageQuerySamples
                val count = e.instruction(opcode, type(IrInt.U32), args[0])
                convertInteger(count, IrInt.U32, resultType)
            }

            Intrinsic.TextureCalculateLod -> {
                e.capability(Spv.CapabilityImageQuery)
                val image = instruction.operands[0].type as IrImage
                val sampled = e.instruction(Spv.OpSampledImage, e.sampledImageType(image), args[0], args[1])
                val lod = e.instruction(Spv.OpImageQueryLod, type(IrVector.of(IrFloat.F32, 2)), sampled, args[2])
                e.instruction(Spv.OpCompositeExtract, type(IrFloat.F32), lod, if (instruction.literals[1] == 1) 0 else 1)
            }

            Intrinsic.AtomicLoad, Intrinsic.AtomicStore, Intrinsic.AtomicExchange, Intrinsic.AtomicCompareExchange,
            Intrinsic.AtomicAdd, Intrinsic.AtomicSub, Intrinsic.AtomicAnd, Intrinsic.AtomicOr, Intrinsic.AtomicXor,
            Intrinsic.AtomicSMin, Intrinsic.AtomicSMax, Intrinsic.AtomicUMin, Intrinsic.AtomicUMax, Intrinsic.AtomicFAdd,
            -> atomic(instruction)

            Intrinsic.SimdSum, Intrinsic.SimdProduct, Intrinsic.SimdMin, Intrinsic.SimdMax, Intrinsic.SimdAnd,
            Intrinsic.SimdOr, Intrinsic.SimdXor, Intrinsic.SimdPrefixInclusiveSum, Intrinsic.SimdPrefixInclusiveProduct,
            Intrinsic.SimdPrefixExclusiveSum, Intrinsic.SimdPrefixExclusiveProduct,
            -> reduction(instruction)

            Intrinsic.SimdBroadcast, Intrinsic.SimdShuffle, Intrinsic.SimdShuffleUp, Intrinsic.SimdShuffleDown,
            Intrinsic.SimdShuffleXor, Intrinsic.QuadBroadcast, Intrinsic.QuadShuffle, Intrinsic.QuadShuffleXor,
            -> shuffle(instruction)

            Intrinsic.SimdBroadcastFirst -> {
                e.capability(Spv.CapabilityGroupNonUniformBallot)
                e.instruction(Spv.OpGroupNonUniformBroadcastFirst, type(resultType), subgroup(), args[0])
            }

            Intrinsic.SimdBallot, Intrinsic.SimdActiveThreadsMask -> {
                e.capability(Spv.CapabilityGroupNonUniformBallot)
                val predicate = if (intrinsic == Intrinsic.SimdBallot) args[0] else e.constant(ConstantScalar.bool(true))
                val uvec4 = IrVector.of(IrInt.U32, 4)
                val ballot = e.instruction(Spv.OpGroupNonUniformBallot, type(uvec4), subgroup(), predicate)
                val low = e.instruction(Spv.OpUConvert, type(IrInt.U64), e.instruction(Spv.OpCompositeExtract, type(IrInt.U32), ballot, 0))
                val high = e.instruction(Spv.OpUConvert, type(IrInt.U64), e.instruction(Spv.OpCompositeExtract, type(IrInt.U32), ballot, 1))
                val shifted = e.instruction(Spv.OpShiftLeftLogical, type(IrInt.U64), high, e.constant(ConstantScalar.int(IrInt.U64, 32)))
                e.instruction(Spv.OpBitwiseOr, type(IrInt.U64), low, shifted)
            }

            Intrinsic.SimdAll, Intrinsic.SimdAny -> {
                e.capability(Spv.CapabilityGroupNonUniformVote)
                val opcode = if (intrinsic == Intrinsic.SimdAll) Spv.OpGroupNonUniformAll else Spv.OpGroupNonUniformAny
                e.instruction(opcode, type(IrBool), subgroup(), args[0])
            }

            Intrinsic.SimdIsFirst -> {
                e.capability(Spv.CapabilityGroupNonUniform)
                e.instruction(Spv.OpGroupNonUniformElect, type(IrBool), subgroup())
            }

            Intrinsic.IsFunctionConstantDefined -> args[0]
            else -> {
                e.error("intrinsic ${intrinsic.name} is not supported by the SPIR-V backend")
                e.constant(gg.sona.msl.ir.Constants.zero(resultType))
            }
        }
    }

    private fun withScalar(type: IrType, scalar: IrScalar): IrType = if (type is IrVector) IrVector.of(scalar, type.count) else scalar

    private fun splat(type: IrType, value: Int): Int =
        e.constant(gg.sona.msl.ir.Constants.splat(type, gg.sona.msl.ir.Constants.integer(type.scalar, value.toLong())))

    private inline fun widened(instruction: Instruction, value: Int, operation: (Int, IrType) -> Int): Int {
        val type = instruction.operands[0].type
        val scalar = type.scalar as IrInt
        val resultType = instruction.type
        if (scalar.bits == 32) {
            val result = operation(value, type)
            return if (type == resultType) result else e.instruction(Spv.OpBitcast, type(resultType), result)
        }
        if (scalar.bits == 64) {
            e.error("64-bit bit manipulation is not supported")
            return value
        }
        val wide = withScalar(type, IrInt.of(32, scalar.signed))
        val extend = e.instruction(if (scalar.signed) Spv.OpSConvert else Spv.OpUConvert, type(wide), value)
        val result = operation(extend, wide)
        val narrowed = e.instruction(if (scalar.signed) Spv.OpSConvert else Spv.OpUConvert, type(type), result)
        return if (type == resultType) narrowed else e.instruction(Spv.OpBitcast, type(resultType), narrowed)
    }

    private fun convertInteger(value: Int, from: IrType, to: IrType): Int = when {
        from == to -> value
        from.scalar.bits == to.scalar.bits -> e.instruction(Spv.OpBitcast, type(to), value)
        else -> e.instruction(Spv.OpUConvert, type(to), value)
    }

    private fun subgroup(): Int = e.constantU32(Spv.ScopeSubgroup)

    private fun barrier(intrinsic: Intrinsic, flags: MemoryFlags) {
        var semantics = 0
        if (flags.threadgroup) semantics = semantics or Spv.MemorySemanticsWorkgroupMemory
        if (flags.device) semantics = semantics or Spv.MemorySemanticsUniformMemory
        if (flags.texture) semantics = semantics or Spv.MemorySemanticsImageMemory
        if (semantics != 0) semantics = semantics or Spv.MemorySemanticsAcquireRelease
        val memoryScope = when {
            intrinsic == Intrinsic.SimdgroupBarrier -> Spv.ScopeSubgroup
            flags.device || flags.texture -> Spv.ScopeDevice
            else -> Spv.ScopeWorkgroup
        }
        when (intrinsic) {
            Intrinsic.MemoryFence -> e.statement(Spv.OpMemoryBarrier, e.constantU32(memoryScope), e.constantU32(semantics))
            else -> {
                val execution = if (intrinsic == Intrinsic.SimdgroupBarrier) Spv.ScopeSubgroup else Spv.ScopeWorkgroup
                e.statement(Spv.OpControlBarrier, e.constantU32(execution), e.constantU32(memoryScope), e.constantU32(semantics))
            }
        }
    }

    private fun coordinate(image: IrImage, coordinate: Int, coordinateType: IrType, arrayIndex: Int?, integer: Boolean): Int {
        if (arrayIndex == null) return coordinate
        val scalar = if (integer) IrInt.U32 else IrFloat.F32
        val layer = if (integer) arrayIndex else e.instruction(Spv.OpConvertUToF, type(IrFloat.F32), arrayIndex)
        val count = coordinateType.componentCount + 1
        return e.instruction(Spv.OpCompositeConstruct, type(IrVector.of(scalar, count)), coordinate, layer)
    }

    private fun offsetOperand(value: gg.sona.msl.ir.Value, operands: IntList): Int {
        operands.add(e.operand(value))
        if (value is gg.sona.msl.ir.IrConstant) return Spv.ImageOperandsConstOffset
        e.capability(Spv.CapabilityImageGatherExtended)
        return Spv.ImageOperandsOffset
    }

    private fun sample(instruction: Instruction): Int {
        val arguments = TextureArguments(instruction)
        val image = instruction.operands[0].type as IrImage
        val sampler = e.operand(arguments.next())
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { e.operand(it) }
        val bias = arguments.take(TextureOperands.Bias)
        var lod = arguments.take(TextureOperands.Lod)
        val gradient = if (TextureOperands.Gradient in arguments.mask) arguments.next() to arguments.next() else null
        val minLod = arguments.take(TextureOperands.MinLod)
        val offset = arguments.take(TextureOperands.Offset)
        val compare = arguments.take(TextureOperands.Compare)
        val sampledImage = e.instruction(Spv.OpSampledImage, e.sampledImageType(image), e.operand(instruction.operands[0]), sampler)
        val coordinate = coordinate(image, e.operand(coordinateValue), coordinateValue.type, arrayIndex, false)
        val explicit = lod != null || gradient != null || e.stage != ShaderStage.Fragment
        var mask = 0
        val operands = IntList()
        if (bias != null && e.stage == ShaderStage.Fragment) {
            mask = mask or Spv.ImageOperandsBias
            operands.add(e.operand(bias))
        }
        if (explicit && gradient == null) {
            mask = mask or Spv.ImageOperandsLod
            operands.add(lod?.let { e.operand(it) } ?: e.constant(ConstantScalar.f32(0f)))
            lod = null
        }
        if (gradient != null) {
            mask = mask or Spv.ImageOperandsGrad
            operands.add(e.operand(gradient.first))
            operands.add(e.operand(gradient.second))
        }
        if (offset != null) mask = mask or offsetOperand(offset, operands)
        if (minLod != null && (!explicit || gradient != null)) {
            e.capability(Spv.CapabilityMinLod)
            mask = mask or Spv.ImageOperandsMinLod
            operands.add(e.operand(minLod))
        }
        val full = IntList()
        full.add(sampledImage)
        full.add(coordinate)
        if (compare != null) full.add(e.operand(compare))
        if (mask != 0) {
            full.add(mask)
            full.addAll(operands)
        }
        val scalar = e.sampledScalar(image)
        return if (compare != null) {
            val opcode = if (explicit) Spv.OpImageSampleDrefExplicitLod else Spv.OpImageSampleDrefImplicitLod
            val result = e.instruction(opcode, type(IrFloat.F32), full)
            adaptScalar(result, IrFloat.F32, instruction.type)
        } else {
            val opcode = if (explicit) Spv.OpImageSampleExplicitLod else Spv.OpImageSampleImplicitLod
            val result = e.instruction(opcode, type(IrVector.of(scalar, 4)), full)
            adaptTexel(result, image, instruction.type)
        }
    }

    private fun gather(instruction: Instruction): Int {
        val arguments = TextureArguments(instruction)
        val image = instruction.operands[0].type as IrImage
        val sampler = e.operand(arguments.next())
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { e.operand(it) }
        val offset = arguments.take(TextureOperands.Offset)
        val compare = arguments.take(TextureOperands.Compare)
        val sampledImage = e.instruction(Spv.OpSampledImage, e.sampledImageType(image), e.operand(instruction.operands[0]), sampler)
        val coordinate = coordinate(image, e.operand(coordinateValue), coordinateValue.type, arrayIndex, false)
        val full = IntList()
        full.add(sampledImage)
        full.add(coordinate)
        if (compare != null) full.add(e.operand(compare)) else full.add(e.constantU32(arguments.extra))
        if (offset != null) {
            val operands = IntList()
            val mask = offsetOperand(offset, operands)
            full.add(mask)
            full.addAll(operands)
        }
        val scalar = e.sampledScalar(image)
        val opcode = if (compare != null) Spv.OpImageDrefGather else Spv.OpImageGather
        val result = e.instruction(opcode, type(IrVector.of(scalar, 4)), full)
        return adaptTexel(result, image, instruction.type)
    }

    private fun read(instruction: Instruction): Int {
        val arguments = TextureArguments(instruction)
        val image = instruction.operands[0].type as IrImage
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { e.operand(it) }
        val lod = arguments.take(TextureOperands.Lod)
        val sample = arguments.take(TextureOperands.SampleIndex)
        val coordinate = coordinate(image, e.operand(coordinateValue), coordinateValue.type, arrayIndex, true)
        val full = IntList()
        full.add(e.operand(instruction.operands[0]))
        full.add(coordinate)
        val storage = image.access.isStorage
        var mask = 0
        val operands = IntList()
        if (!storage && !image.multisampled && image.dim != ImageDim.Buffer) {
            mask = mask or Spv.ImageOperandsLod
            operands.add(lod?.let { e.operand(it) } ?: e.constantU32(0))
        }
        if (sample != null) {
            mask = mask or Spv.ImageOperandsSample
            operands.add(e.operand(sample))
        }
        if (mask != 0) {
            full.add(mask)
            full.addAll(operands)
        }
        val scalar = e.sampledScalar(image)
        val opcode = if (storage) Spv.OpImageRead else Spv.OpImageFetch
        val result = e.instruction(opcode, type(IrVector.of(scalar, 4)), full)
        return adaptTexel(result, image, instruction.type)
    }

    private fun write(instruction: Instruction) {
        val arguments = TextureArguments(instruction)
        val image = instruction.operands[0].type as IrImage
        val coordinateValue = arguments.next()
        val arrayIndex = arguments.take(TextureOperands.ArrayIndex)?.let { e.operand(it) }
        arguments.take(TextureOperands.Lod)
        val texelValue = arguments.next()
        val coordinate = coordinate(image, e.operand(coordinateValue), coordinateValue.type, arrayIndex, true)
        val scalar = e.sampledScalar(image)
        val texelType = IrVector.of(scalar, 4)
        var texel = e.operand(texelValue)
        if (texelValue.type != texelType) {
            texel = when (image.sampled) {
                SampledKind.Half -> e.instruction(Spv.OpFConvert, type(texelType), texel)
                SampledKind.SShort -> e.instruction(Spv.OpSConvert, type(texelType), texel)
                SampledKind.UShort -> e.instruction(Spv.OpUConvert, type(texelType), texel)
                else -> e.instruction(Spv.OpBitcast, type(texelType), texel)
            }
        }
        e.statement(Spv.OpImageWrite, e.operand(instruction.operands[0]), coordinate, texel)
    }

    private fun size(instruction: Instruction): Int {
        e.capability(Spv.CapabilityImageQuery)
        val arguments = TextureArguments(instruction)
        val image = instruction.operands[0].type as IrImage
        val lod = arguments.take(TextureOperands.Lod)
        val dimensions = when (image.dim) {
            ImageDim.Dim1D, ImageDim.Buffer -> 1
            ImageDim.Dim2D, ImageDim.Cube -> 2
            ImageDim.Dim3D -> 3
        } + if (image.arrayed) 1 else 0
        val resultType = if (dimensions == 1) IrInt.U32 else IrVector.of(IrInt.U32, dimensions)
        val useLod = !image.access.isStorage && !image.multisampled && image.dim != ImageDim.Buffer
        val query = if (useLod) {
            e.instruction(Spv.OpImageQuerySizeLod, type(resultType), e.operand(instruction.operands[0]), lod?.let { e.operand(it) } ?: e.constantU32(0))
        } else {
            e.instruction(Spv.OpImageQuerySize, type(resultType), e.operand(instruction.operands[0]))
        }
        val component = if (arguments.extra == 3) dimensions - 1 else arguments.extra
        val value = if (dimensions == 1) query else e.instruction(Spv.OpCompositeExtract, type(IrInt.U32), query, component)
        return convertInteger(value, IrInt.U32, instruction.type)
    }

    private fun adaptScalar(value: Int, from: IrType, to: IrType): Int = when {
        from == to -> value
        to is IrFloat -> e.instruction(Spv.OpFConvert, type(to), value)
        else -> value
    }

    private fun adaptTexel(value: Int, image: IrImage, target: IrType): Int {
        val scalar = e.sampledScalar(image)
        val source = IrVector.of(scalar, 4)
        var current = value
        var currentType: IrType = source
        if (target is IrScalar) {
            current = e.instruction(Spv.OpCompositeExtract, type(scalar), current, 0)
            currentType = scalar
        }
        if (currentType == target) return current
        return when (target.scalar) {
            is IrFloat -> e.instruction(Spv.OpFConvert, type(target), current)
            is IrInt -> if (target.scalar.bits == currentType.scalar.bits) {
                e.instruction(Spv.OpBitcast, type(target), current)
            } else {
                e.instruction(if ((target.scalar as IrInt).signed) Spv.OpSConvert else Spv.OpUConvert, type(target), current)
            }

            else -> current
        }
    }

    private fun atomic(instruction: Instruction): Int? {
        val intrinsic = instruction.intrinsic!!
        val chain = e.chain(instruction.operands[0])
        val pointer = e.materialize(chain)
        val scope = e.constantU32(if (instruction.literals.getOrElse(0) { 0 } == 1) Spv.ScopeWorkgroup else Spv.ScopeDevice)
        val semantics = e.constantU32(Spv.MemorySemanticsNone)
        val element = chain.pointee
        if (element.scalar.bits == 64) e.capability(Spv.CapabilityInt64Atomics)
        val resultType = type(element)
        return when (intrinsic) {
            Intrinsic.AtomicLoad -> e.instruction(Spv.OpAtomicLoad, resultType, pointer, scope, semantics)
            Intrinsic.AtomicStore -> {
                e.statement(Spv.OpAtomicStore, pointer, scope, semantics, operand(instruction, 1))
                null
            }

            Intrinsic.AtomicCompareExchange -> e.instruction(
                Spv.OpAtomicCompareExchange,
                resultType,
                pointer,
                scope,
                semantics,
                semantics,
                operand(instruction, 1),
                operand(instruction, 2),
            )

            Intrinsic.AtomicFAdd -> {
                e.extension("SPV_EXT_shader_atomic_float_add")
                e.capability(Spv.CapabilityAtomicFloat32AddEXT)
                e.instruction(Spv.OpAtomicFAddEXT, resultType, pointer, scope, semantics, operand(instruction, 1))
            }

            else -> {
                val opcode = when (intrinsic) {
                    Intrinsic.AtomicExchange -> Spv.OpAtomicExchange
                    Intrinsic.AtomicAdd -> Spv.OpAtomicIAdd
                    Intrinsic.AtomicSub -> Spv.OpAtomicISub
                    Intrinsic.AtomicAnd -> Spv.OpAtomicAnd
                    Intrinsic.AtomicOr -> Spv.OpAtomicOr
                    Intrinsic.AtomicXor -> Spv.OpAtomicXor
                    Intrinsic.AtomicSMin -> Spv.OpAtomicSMin
                    Intrinsic.AtomicSMax -> Spv.OpAtomicSMax
                    Intrinsic.AtomicUMin -> Spv.OpAtomicUMin
                    else -> Spv.OpAtomicUMax
                }
                e.instruction(opcode, resultType, pointer, scope, semantics, operand(instruction, 1))
            }
        }
    }

    private fun reduction(instruction: Instruction): Int {
        e.capability(Spv.CapabilityGroupNonUniformArithmetic)
        val intrinsic = instruction.intrinsic!!
        val scalar = instruction.type.scalar
        val isFloat = scalar is IrFloat
        val signed = scalar is IrInt && scalar.signed
        val operation = when (intrinsic) {
            Intrinsic.SimdPrefixInclusiveSum, Intrinsic.SimdPrefixInclusiveProduct -> Spv.GroupOperationInclusiveScan
            Intrinsic.SimdPrefixExclusiveSum, Intrinsic.SimdPrefixExclusiveProduct -> Spv.GroupOperationExclusiveScan
            else -> Spv.GroupOperationReduce
        }
        val opcode = when (intrinsic) {
            Intrinsic.SimdSum, Intrinsic.SimdPrefixInclusiveSum, Intrinsic.SimdPrefixExclusiveSum ->
                if (isFloat) Spv.OpGroupNonUniformFAdd else Spv.OpGroupNonUniformIAdd

            Intrinsic.SimdProduct, Intrinsic.SimdPrefixInclusiveProduct, Intrinsic.SimdPrefixExclusiveProduct ->
                if (isFloat) Spv.OpGroupNonUniformFMul else Spv.OpGroupNonUniformIMul

            Intrinsic.SimdMin -> if (isFloat) Spv.OpGroupNonUniformFMin else if (signed) Spv.OpGroupNonUniformSMin else Spv.OpGroupNonUniformUMin
            Intrinsic.SimdMax -> if (isFloat) Spv.OpGroupNonUniformFMax else if (signed) Spv.OpGroupNonUniformSMax else Spv.OpGroupNonUniformUMax
            Intrinsic.SimdAnd -> Spv.OpGroupNonUniformBitwiseAnd
            Intrinsic.SimdOr -> Spv.OpGroupNonUniformBitwiseOr
            else -> Spv.OpGroupNonUniformBitwiseXor
        }
        return e.instruction(opcode, type(instruction.type), subgroup(), operation, operand(instruction, 0))
    }

    private fun shuffle(instruction: Instruction): Int {
        val intrinsic = instruction.intrinsic!!
        val value = operand(instruction, 0)
        val laneValue = instruction.operands[1]
        var lane = e.operand(laneValue)
        if (laneValue.type != IrInt.U32) lane = e.instruction(Spv.OpUConvert, type(IrInt.U32), lane)
        val resultType = type(instruction.type)
        return when (intrinsic) {
            Intrinsic.SimdShuffleUp, Intrinsic.SimdShuffleDown -> {
                e.capability(Spv.CapabilityGroupNonUniformShuffleRelative)
                val opcode = if (intrinsic == Intrinsic.SimdShuffleUp) Spv.OpGroupNonUniformShuffleUp else Spv.OpGroupNonUniformShuffleDown
                e.instruction(opcode, resultType, subgroup(), value, lane)
            }

            Intrinsic.SimdShuffleXor, Intrinsic.QuadShuffleXor -> {
                e.capability(Spv.CapabilityGroupNonUniformShuffle)
                val mask = if (intrinsic == Intrinsic.QuadShuffleXor) {
                    e.instruction(Spv.OpBitwiseAnd, type(IrInt.U32), lane, e.constantU32(3))
                } else {
                    lane
                }
                e.instruction(Spv.OpGroupNonUniformShuffleXor, resultType, subgroup(), value, mask)
            }

            Intrinsic.QuadBroadcast, Intrinsic.QuadShuffle -> {
                e.capability(Spv.CapabilityGroupNonUniformShuffle)
                val invocation = e.synthesizedInput(BuiltinVariable.SubgroupLocalInvocationId, IrInt.U32)
                val base = e.instruction(Spv.OpBitwiseAnd, type(IrInt.U32), invocation, e.constantU32(3.inv()))
                val within = e.instruction(Spv.OpBitwiseAnd, type(IrInt.U32), lane, e.constantU32(3))
                val target = e.instruction(Spv.OpBitwiseOr, type(IrInt.U32), base, within)
                e.instruction(Spv.OpGroupNonUniformShuffle, resultType, subgroup(), value, target)
            }

            else -> {
                e.capability(Spv.CapabilityGroupNonUniformShuffle)
                e.instruction(Spv.OpGroupNonUniformShuffle, resultType, subgroup(), value, lane)
            }
        }
    }

    companion object {
        private val GLSL = mapOf(
            Intrinsic.Rint to Spv.GlslRoundEven,
            Intrinsic.Trunc to Spv.GlslTrunc,
            Intrinsic.FAbs to Spv.GlslFAbs,
            Intrinsic.SAbs to Spv.GlslSAbs,
            Intrinsic.FSign to Spv.GlslFSign,
            Intrinsic.Floor to Spv.GlslFloor,
            Intrinsic.Ceil to Spv.GlslCeil,
            Intrinsic.Sin to Spv.GlslSin,
            Intrinsic.Cos to Spv.GlslCos,
            Intrinsic.Tan to Spv.GlslTan,
            Intrinsic.Asin to Spv.GlslAsin,
            Intrinsic.Acos to Spv.GlslAcos,
            Intrinsic.Atan to Spv.GlslAtan,
            Intrinsic.Sinh to Spv.GlslSinh,
            Intrinsic.Cosh to Spv.GlslCosh,
            Intrinsic.Tanh to Spv.GlslTanh,
            Intrinsic.Asinh to Spv.GlslAsinh,
            Intrinsic.Acosh to Spv.GlslAcosh,
            Intrinsic.Atanh to Spv.GlslAtanh,
            Intrinsic.Atan2 to Spv.GlslAtan2,
            Intrinsic.Pow to Spv.GlslPow,
            Intrinsic.Exp to Spv.GlslExp,
            Intrinsic.Log to Spv.GlslLog,
            Intrinsic.Exp2 to Spv.GlslExp2,
            Intrinsic.Log2 to Spv.GlslLog2,
            Intrinsic.Sqrt to Spv.GlslSqrt,
            Intrinsic.Rsqrt to Spv.GlslInverseSqrt,
            Intrinsic.Determinant to Spv.GlslDeterminant,
            Intrinsic.FMax to Spv.GlslFMax,
            Intrinsic.UMin to Spv.GlslUMin,
            Intrinsic.SMin to Spv.GlslSMin,
            Intrinsic.UMax to Spv.GlslUMax,
            Intrinsic.SMax to Spv.GlslSMax,
            Intrinsic.FClamp to Spv.GlslFClamp,
            Intrinsic.UClamp to Spv.GlslUClamp,
            Intrinsic.SClamp to Spv.GlslSClamp,
            Intrinsic.Mix to Spv.GlslFMix,
            Intrinsic.Step to Spv.GlslStep,
            Intrinsic.Smoothstep to Spv.GlslSmoothStep,
            Intrinsic.Fma to Spv.GlslFma,
            Intrinsic.Ldexp to Spv.GlslLdexp,
            Intrinsic.PackSnorm4x8 to Spv.GlslPackSnorm4x8,
            Intrinsic.PackUnorm4x8 to Spv.GlslPackUnorm4x8,
            Intrinsic.PackSnorm2x16 to Spv.GlslPackSnorm2x16,
            Intrinsic.PackUnorm2x16 to Spv.GlslPackUnorm2x16,
            Intrinsic.UnpackSnorm2x16 to Spv.GlslUnpackSnorm2x16,
            Intrinsic.UnpackUnorm2x16 to Spv.GlslUnpackUnorm2x16,
            Intrinsic.UnpackSnorm4x8 to Spv.GlslUnpackSnorm4x8,
            Intrinsic.UnpackUnorm4x8 to Spv.GlslUnpackUnorm4x8,
            Intrinsic.Length to Spv.GlslLength,
            Intrinsic.Distance to Spv.GlslDistance,
            Intrinsic.Cross to Spv.GlslCross,
            Intrinsic.Normalize to Spv.GlslNormalize,
            Intrinsic.FaceForward to Spv.GlslFaceForward,
            Intrinsic.Reflect to Spv.GlslReflect,
            Intrinsic.Refract to Spv.GlslRefract,
        )

        private val NATIVE: Set<Intrinsic> = GLSL.keys + setOf(
            Intrinsic.FMin, Intrinsic.Dot, Intrinsic.Transpose, Intrinsic.IsNan, Intrinsic.IsInf, Intrinsic.All,
            Intrinsic.Any, Intrinsic.Dfdx, Intrinsic.Dfdy, Intrinsic.Fwidth, Intrinsic.Popcount, Intrinsic.ReverseBits,
            Intrinsic.Clz, Intrinsic.Ctz, Intrinsic.SExtractBits, Intrinsic.UExtractBits, Intrinsic.InsertBits,
            Intrinsic.SMulHi, Intrinsic.UMulHi, Intrinsic.FrexpMantissa, Intrinsic.FrexpExponent,
        )

        fun isNative(intrinsic: Intrinsic): Boolean = intrinsic in NATIVE
    }
}
