package gg.sona.msl.spirv

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.DepthMode
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.ImageAccess
import gg.sona.msl.ir.ImageDim
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Interpolation
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Parameter
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.ir.SampledKind
import gg.sona.msl.ir.Sampling
import gg.sona.msl.ir.SpecConstant
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.passes.ControlFlowGraph
import gg.sona.msl.reflect.ResourceBindingRequest
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.util.HalfFloat
import gg.sona.msl.util.IntList

class SpirvEmitter(
    private val module: IrModule,
    val entry: EntryPoint,
    val options: SpirvOptions,
    private val diagnostics: Diagnostics,
) {
    private var nextId = 1
    private val capabilities = LinkedHashSet<Int>()
    private val extensions = LinkedHashSet<String>()
    private val entryPoints = SpirvSection()
    private val executionModes = SpirvSection()
    private val debug = SpirvSection()
    private val annotations = SpirvSection()
    val declarations = SpirvSection()
    private val functions = SpirvSection()
    lateinit var body: SpirvSection
        private set
    private var glsl = 0
    private val types = HashMap<Any, Int>()
    private val constants = HashMap<Pair<IrConstant, SpirvLayout>, Int>()
    private val values = HashMap<Value, Int>()
    private val globals = HashMap<GlobalVariable, SpirvGlobal>()
    private val chains = HashMap<Value, PointerChain>()
    private val blocks = HashMap<Block, Int>()
    private val synthesizedInputs = HashMap<BuiltinVariable, SpirvGlobal>()
    private val interfaceIds = LinkedHashSet<Int>()
    private val intrinsics = SpirvIntrinsicEmitter(this)
    var hasErrors = false
        private set

    val version: SpirvVersion
        get() = options.version

    val stage: ShaderStage
        get() = entry.stage

    fun error(message: String) {
        hasErrors = true
        diagnostics.error(SourceLocation.NONE, "SPIR-V: $message")
    }

    fun id(): Int = nextId++

    fun capability(capability: Int) {
        capabilities.add(capability)
    }

    fun extension(name: String) {
        extensions.add(name)
    }

    fun glslImport(): Int {
        if (glsl == 0) glsl = id()
        return glsl
    }

    fun emit(): IntArray {
        capability(Spv.CapabilityShader)
        val functionId = emitFunction()
        val interfaceList = IntList()
        for (id in interfaceIds) interfaceList.add(id)
        val model = when (entry.stage) {
            ShaderStage.Vertex -> Spv.ExecutionModelVertex
            ShaderStage.Fragment -> Spv.ExecutionModelFragment
            ShaderStage.Kernel -> Spv.ExecutionModelGLCompute
        }
        entryPoints.instructionWithString(Spv.OpEntryPoint, intArrayOf(model, functionId), entry.name, interfaceList.toIntArray())
        emitExecutionModes(functionId)
        val header = SpirvSection()
        for (capability in capabilities) header.instruction(Spv.OpCapability, capability)
        for (extension in extensions) header.instructionWithString(Spv.OpExtension, IntArray(0), extension)
        if (glsl != 0) header.instructionWithString(Spv.OpExtInstImport, intArrayOf(glsl), "GLSL.std.450")
        header.instruction(Spv.OpMemoryModel, Spv.AddressingLogical, Spv.MemoryModelGLSL450)
        val words = IntList(1024)
        words.add(Spv.MAGIC)
        words.add(options.version.word)
        words.add(Spv.GENERATOR)
        words.add(nextId)
        words.add(0)
        for (section in listOf(header, entryPoints, executionModes, debug, annotations, declarations, functions)) {
            words.addAll(section.words)
        }
        return words.toIntArray()
    }

    private fun emitExecutionModes(function: Int) {
        when (entry.stage) {
            ShaderStage.Fragment -> {
                executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeOriginUpperLeft)
                if (entry.earlyFragmentTests) executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeEarlyFragmentTests)
                when (entry.depthMode) {
                    DepthMode.Any -> executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeDepthReplacing)
                    DepthMode.Greater -> {
                        executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeDepthReplacing)
                        executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeDepthGreater)
                    }

                    DepthMode.Less -> {
                        executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeDepthReplacing)
                        executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeDepthLess)
                    }

                    null -> Unit
                }
            }

            ShaderStage.Kernel -> {
                val size = entry.workgroupSize
                executionModes.instruction(Spv.OpExecutionMode, function, Spv.ExecutionModeLocalSize, size.x, size.y, size.z)
                if (size.specializable) {
                    val components = module.specConstants.filter { it.name.startsWith("threads_per_threadgroup.") }.sortedBy { it.specId }
                    if (components.size == 3) {
                        val composite = id()
                        val operands = IntList()
                        operands.add(type(UINT3))
                        operands.add(composite)
                        for (component in components) operands.add(value(component))
                        declarations.instruction(Spv.OpSpecConstantComposite, operands)
                        annotations.instruction(Spv.OpDecorate, composite, Spv.DecorationBuiltIn, Spv.BuiltInWorkgroupSize)
                    }
                }
            }

            ShaderStage.Vertex -> Unit
        }
    }

    fun name(id: Int, name: String) {
        if (options.emitNames && name.isNotEmpty()) debug.instructionWithString(Spv.OpName, intArrayOf(id), name)
    }

    fun decorate(id: Int, decoration: Int, vararg operands: Int) {
        annotations.instruction(Spv.OpDecorate, id, decoration, *operands)
    }

    private fun memberDecorate(id: Int, member: Int, decoration: Int, vararg operands: Int) {
        annotations.instruction(Spv.OpMemberDecorate, id, member, decoration, *operands)
    }

    fun declare(opcode: Int, resultType: Int, vararg operands: Int): Int {
        val result = id()
        declarations.instruction(opcode, resultType, result, *operands)
        return result
    }

    fun instruction(opcode: Int, resultType: Int, vararg operands: Int): Int {
        val result = id()
        body.instruction(opcode, resultType, result, *operands)
        return result
    }

    fun instruction(opcode: Int, resultType: Int, operands: IntList): Int {
        val result = id()
        val list = IntList(operands.size + 2)
        list.add(resultType)
        list.add(result)
        list.addAll(operands)
        body.instruction(opcode, list)
        return result
    }

    fun statement(opcode: Int, vararg operands: Int) {
        body.instruction(opcode, *operands)
    }

    fun extended(instruction: Int, resultType: Int, vararg operands: Int): Int =
        instruction(Spv.OpExtInst, resultType, glslImport(), instruction, *operands)

    fun type(type: IrType, layout: SpirvLayout = SpirvLayout.Logical): Int {
        if (layout == SpirvLayout.Explicit) {
            if (type is IrBool) return type(IrInt.U8)
            if (type is IrVector && type.element is IrBool) return type(IrVector.of(IrInt.U8, type.count))
        }
        val key: Any = when (type) {
            is IrArray, is IrStruct -> type to layout
            is IrImage -> listOf(
                "image",
                type.dim,
                sampledScalar(type),
                type.depth,
                type.arrayed,
                type.multisampled,
                type.access == ImageAccess.Write || type.access == ImageAccess.ReadWrite,
            )
            else -> type
        }
        types[key]?.let { return it }
        val result = when (type) {
            is IrVoid -> declareType(Spv.OpTypeVoid)
            is IrBool -> declareType(Spv.OpTypeBool)
            is IrInt -> {
                when (type.bits) {
                    8 -> capability(Spv.CapabilityInt8)
                    16 -> capability(Spv.CapabilityInt16)
                    64 -> capability(Spv.CapabilityInt64)
                }
                declareType(Spv.OpTypeInt, type.bits, if (type.signed) 1 else 0)
            }

            is IrFloat -> {
                if (type.bits == 16) capability(Spv.CapabilityFloat16)
                if (type.bits == 64) capability(Spv.CapabilityFloat64)
                declareType(Spv.OpTypeFloat, type.bits)
            }

            is IrVector -> declareType(Spv.OpTypeVector, type(type.element), type.count)
            is IrMatrix -> declareType(Spv.OpTypeMatrix, type(type.column), type.columns)
            is IrArray -> arrayType(type, layout)
            is IrStruct -> structType(type, layout)
            is IrPointer -> declareType(Spv.OpTypePointer, storageClass(type.storage), type(type.pointee, layoutFor(type.storage)))
            is IrImage -> imageType(type)
            is IrSampler -> declareType(Spv.OpTypeSampler)
        }
        types[key] = result
        return result
    }

    fun pointerType(pointee: IrType, storage: StorageClass): Int = type(IrPointer(pointee, storage))

    private fun declareType(opcode: Int, vararg operands: Int): Int {
        val result = id()
        declarations.instruction(opcode, result, *operands)
        return result
    }

    fun layoutFor(storage: StorageClass): SpirvLayout =
        if (storage.isExplicitlyLaidOut) SpirvLayout.Explicit else SpirvLayout.Logical

    private fun arrayType(type: IrArray, layout: SpirvLayout): Int {
        val element = type(type.element, layout)
        val result = if (type.isRuntime) {
            declareType(Spv.OpTypeRuntimeArray, element)
        } else {
            declareType(Spv.OpTypeArray, element, constant(ConstantScalar.u32(type.length)))
        }
        if (layout == SpirvLayout.Explicit) decorate(result, Spv.DecorationArrayStride, type.stride)
        return result
    }

    private fun structType(type: IrStruct, layout: SpirvLayout): Int {
        val members = type.members.map { type(it.type, layout) }
        val result = declareType(Spv.OpTypeStruct, *members.toIntArray())
        name(result, type.name)
        type.members.forEachIndexed { index, member ->
            if (options.emitNames) debug.instructionWithString(Spv.OpMemberName, intArrayOf(result, index), member.name)
            if (layout == SpirvLayout.Explicit) {
                checkExplicitMember(member.type)
                memberDecorate(result, index, Spv.DecorationOffset, member.offset)
                decorateMatrix(result, index, member.type)
            }
        }
        return result
    }

    private fun checkExplicitMember(type: IrType) {
        when (type) {
            is IrBool -> storageCapability(IrInt.U8)
            is IrVector -> checkExplicitMember(type.element)
            is IrArray -> checkExplicitMember(type.element)
            is IrScalar -> storageCapability(type)
            else -> Unit
        }
    }

    private fun storageCapability(type: IrScalar) {
        if (type.bits == 16) {
            capability(Spv.CapabilityStorageBuffer16BitAccess)
            capability(Spv.CapabilityUniformAndStorageBuffer16BitAccess)
        }
        if (type.bits == 8) {
            if (!version.atLeast(SpirvVersion.V1_5)) extension("SPV_KHR_8bit_storage")
            capability(Spv.CapabilityStorageBuffer8BitAccess)
            capability(Spv.CapabilityUniformAndStorageBuffer8BitAccess)
        }
    }

    private fun decorateMatrix(struct: Int, member: Int, type: IrType) {
        var current = type
        while (current is IrArray) current = current.element
        if (current is IrMatrix) {
            memberDecorate(struct, member, Spv.DecorationColMajor)
            val element = current.column.element.bits / 8
            val lanes = if (current.rows == 3) 4 else current.rows
            memberDecorate(struct, member, Spv.DecorationMatrixStride, lanes * element)
        }
    }

    private fun imageType(type: IrImage): Int {
        val dim = when (type.dim) {
            ImageDim.Dim1D -> {
                capability(if (type.access.isStorage) Spv.CapabilityImage1D else Spv.CapabilitySampled1D)
                Spv.Dim1D
            }

            ImageDim.Dim2D -> Spv.Dim2D
            ImageDim.Dim3D -> Spv.Dim3D
            ImageDim.Cube -> {
                if (type.arrayed) capability(if (type.access.isStorage) Spv.CapabilityImageCubeArray else Spv.CapabilitySampledCubeArray)
                Spv.DimCube
            }

            ImageDim.Buffer -> {
                capability(if (type.access.isStorage) Spv.CapabilityImageBuffer else Spv.CapabilitySampledBuffer)
                Spv.DimBuffer
            }
        }
        val storage = type.access == ImageAccess.Write || type.access == ImageAccess.ReadWrite
        if (storage) {
            if (type.access == ImageAccess.ReadWrite) capability(Spv.CapabilityStorageImageReadWithoutFormat)
            capability(Spv.CapabilityStorageImageWriteWithoutFormat)
            if (type.multisampled) {
                capability(Spv.CapabilityStorageImageMultisample)
                if (type.arrayed) capability(Spv.CapabilityImageMSArray)
            }
        }
        val sampled = type(sampledScalar(type))
        return declareType(
            Spv.OpTypeImage,
            sampled,
            dim,
            if (type.depth) 1 else 0,
            if (type.arrayed) 1 else 0,
            if (type.multisampled) 1 else 0,
            if (storage) 2 else 1,
            Spv.ImageFormatUnknown,
        )
    }

    fun sampledScalar(type: IrImage): IrScalar = when (type.sampled) {
        SampledKind.Float, SampledKind.Half -> IrFloat.F32
        SampledKind.SInt, SampledKind.SShort -> IrInt.I32
        SampledKind.UInt, SampledKind.UShort -> IrInt.U32
    }

    fun sampledImageType(image: IrImage): Int {
        val key = "sampled" to type(image)
        types[key]?.let { return it }
        val result = declareType(Spv.OpTypeSampledImage, type(image))
        types[key] = result
        return result
    }

    fun structOf(vararg members: IrType): Int {
        val key = "anon" to members.toList()
        types[key]?.let { return it }
        val result = declareType(Spv.OpTypeStruct, *members.map { type(it) }.toIntArray())
        types[key] = result
        return result
    }

    private fun storageClass(storage: StorageClass): Int = when (storage) {
        StorageClass.Function -> Spv.StorageClassFunction
        StorageClass.Private -> Spv.StorageClassPrivate
        StorageClass.Workgroup -> Spv.StorageClassWorkgroup
        StorageClass.Uniform -> Spv.StorageClassUniform
        StorageClass.StorageBuffer -> Spv.StorageClassStorageBuffer
        StorageClass.Input -> Spv.StorageClassInput
        StorageClass.Output -> Spv.StorageClassOutput
        StorageClass.UniformConstant -> Spv.StorageClassUniformConstant
        StorageClass.PushConstant -> Spv.StorageClassPushConstant
    }

    fun constant(constant: IrConstant, layout: SpirvLayout = SpirvLayout.Logical): Int {
        val key = constant to layout
        constants[key]?.let { return it }
        val resultType = type(constant.type, layout)
        val result = when (constant) {
            is ConstantScalar -> when (val type = constant.type) {
                is IrBool -> declare(if (constant.asBoolean) Spv.OpConstantTrue else Spv.OpConstantFalse, resultType)
                is IrInt -> when (type.bits) {
                    64 -> declare(Spv.OpConstant, resultType, constant.bits.toInt(), (constant.bits ushr 32).toInt())
                    32 -> declare(Spv.OpConstant, resultType, constant.bits.toInt())
                    else -> {
                        val mask = (1L shl type.bits) - 1
                        val word = if (type.signed) constant.bits.toInt() else (constant.bits and mask).toInt()
                        declare(Spv.OpConstant, resultType, word)
                    }
                }

                is IrFloat -> when (type.bits) {
                    16 -> declare(Spv.OpConstant, resultType, HalfFloat.fromFloat(constant.asDouble.toFloat()))
                    64 -> {
                        val raw = constant.bits
                        declare(Spv.OpConstant, resultType, raw.toInt(), (raw ushr 32).toInt())
                    }

                    else -> declare(Spv.OpConstant, resultType, constant.asDouble.toFloat().toRawBits())
                }
            }

            is ConstantComposite -> {
                val elements = constant.elements.map { constant(it, layout) }
                declare(Spv.OpConstantComposite, resultType, *elements.toIntArray())
            }

            is ConstantNull -> declare(Spv.OpConstantNull, resultType)
            is Undef -> declare(Spv.OpUndef, resultType)
        }
        constants[key] = result
        return result
    }

    fun constantU32(value: Int): Int = constant(ConstantScalar.u32(value))

    fun constantI32(value: Int): Int = constant(ConstantScalar.i32(value))

    fun value(value: Value): Int {
        values[value]?.let { return it }
        return when (value) {
            is IrConstant -> constant(value)
            is SpecConstant -> specConstant(value)
            is GlobalVariable -> materialize(chain(value))
            is Parameter -> error("unexpected parameter").let { 0 }
            is Instruction -> {
                error("value %${value.id} (${value.opcode}) used before definition")
                0
            }
        }
    }

    private fun specConstant(value: SpecConstant): Int {
        val resultType = type(value.type)
        val result = when (value.type) {
            is IrBool -> declare(if (value.default.asBoolean) Spv.OpSpecConstantTrue else Spv.OpSpecConstantFalse, resultType)
            is IrFloat -> if (value.type.bits == 16) {
                declare(Spv.OpSpecConstant, resultType, HalfFloat.fromFloat(value.default.asDouble.toFloat()))
            } else {
                declare(Spv.OpSpecConstant, resultType, value.default.asDouble.toFloat().toRawBits())
            }

            is IrInt -> if (value.type.bits == 64) {
                declare(Spv.OpSpecConstant, resultType, value.default.bits.toInt(), (value.default.bits ushr 32).toInt())
            } else {
                declare(Spv.OpSpecConstant, resultType, value.default.bits.toInt())
            }
        }
        decorate(result, Spv.DecorationSpecId, value.specId)
        name(result, value.name)
        values[value] = result
        return result
    }

    private fun global(variable: GlobalVariable): SpirvGlobal {
        globals[variable]?.let { return it }
        val resource = variable.resource
        var storage = variable.storage
        if (resource?.kind == ResourceKind.UniformBuffer && resource.mslIndex in options.pushConstantBuffers) {
            storage = StorageClass.PushConstant
        }
        val result: SpirvGlobal
        if (storage.isExplicitlyLaidOut) {
            val inner = type(variable.valueType, SpirvLayout.Explicit)
            val wrapper = declareType(Spv.OpTypeStruct, inner)
            decorate(wrapper, Spv.DecorationBlock)
            memberDecorate(wrapper, 0, Spv.DecorationOffset, 0)
            decorateMatrix(wrapper, 0, variable.valueType)
            checkExplicitMember(variable.valueType)
            if (resource?.readOnly == true && storage == StorageClass.StorageBuffer) {
                memberDecorate(wrapper, 0, Spv.DecorationNonWritable)
            }
            name(wrapper, "${variable.name}.block")
            val pointer = declareType(Spv.OpTypePointer, storageClass(storage), wrapper)
            val id = declare(Spv.OpVariable, pointer, storageClass(storage))
            result = SpirvGlobal(id, variable, listOf(constantI32(0)), storage)
        } else {
            val sampleMask = variable.interfaceInfo?.builtin.let {
                it == BuiltinVariable.SampleMaskIn || it == BuiltinVariable.SampleMaskOut
            }
            val valueType = if (sampleMask) IrArray(variable.valueType, 1, 4) else variable.valueType
            val lengthOverride = variable.lengthSpecialization
            val typeId = if (lengthOverride != null && valueType is IrArray) {
                val element = type(valueType.element)
                val array = declareType(Spv.OpTypeArray, element, value(lengthOverride))
                declareType(Spv.OpTypePointer, storageClass(storage), array)
            } else {
                pointerType(valueType, storage)
            }
            val initializer = variable.initializer
            val id = if (initializer != null) {
                declare(Spv.OpVariable, typeId, storageClass(storage), constant(initializer))
            } else {
                declare(Spv.OpVariable, typeId, storageClass(storage))
            }
            result = SpirvGlobal(id, variable, if (sampleMask) listOf(constantI32(0)) else emptyList(), storage)
        }
        name(result.id, variable.name)
        globals[variable] = result
        decorateGlobal(result, storage)
        if (storage == StorageClass.Input || storage == StorageClass.Output || version.atLeast(SpirvVersion.V1_4)) {
            interfaceIds.add(result.id)
        }
        return result
    }

    private fun decorateGlobal(global: SpirvGlobal, storage: StorageClass) {
        val variable = global.variable
        val id = global.id
        variable.resource?.let { resource ->
            if (storage == StorageClass.PushConstant) return@let
            val constexprIndex = if (resource.isConstexprSampler) {
                SpirvBindingLayout.CONSTEXPR_SAMPLER_BASE + module.globals.filter { it.resource?.isConstexprSampler == true }.indexOf(variable)
            } else {
                resource.mslIndex
            }
            val location = options.bindings.locate(
                ResourceBindingRequest(variable.name, resource.kind, constexprIndex, resource.isConstexprSampler, resource.argumentBuffer),
            )
            resource.set = location.set
            resource.binding = location.binding
            decorate(id, Spv.DecorationDescriptorSet, location.set)
            decorate(id, Spv.DecorationBinding, location.binding)
            val image = (variable.valueType as? IrImage) ?: ((variable.valueType as? IrArray)?.element as? IrImage)
            if (image != null && image.access == ImageAccess.Write) decorate(id, Spv.DecorationNonReadable)
        }
        val info = variable.interfaceInfo ?: return
        val builtin = info.builtin
        if (builtin != null) {
            decorate(id, Spv.DecorationBuiltIn, builtinId(builtin))
            if (info.invariant) decorate(id, Spv.DecorationInvariant)
            if (entry.stage == ShaderStage.Fragment && info.isInput && isIntegerLike(variable.valueType)) {
                decorate(id, Spv.DecorationFlat)
            }
            return
        }
        decorate(id, Spv.DecorationLocation, info.location)
        if (info.index != 0) decorate(id, Spv.DecorationIndex, info.index)
        if (info.invariant) decorate(id, Spv.DecorationInvariant)
        if (info.isInput && entry.stage == ShaderStage.Fragment) {
            when (info.interpolation) {
                Interpolation.Flat -> decorate(id, Spv.DecorationFlat)
                Interpolation.NoPerspective -> decorate(id, Spv.DecorationNoPerspective)
                Interpolation.Perspective -> Unit
            }
            when (info.sampling) {
                Sampling.Centroid -> decorate(id, Spv.DecorationCentroid)
                Sampling.Sample -> {
                    capability(Spv.CapabilitySampleRateShading)
                    decorate(id, Spv.DecorationSample)
                }

                Sampling.Center -> Unit
            }
        }
        if (variable.valueType.scalar.bits == 16) capability(Spv.CapabilityStorageInputOutput16)
    }

    private fun isIntegerLike(type: IrType): Boolean = type is IrInt || (type is IrVector && type.element is IrInt) ||
        (type is IrArray && isIntegerLike(type.element))

    private fun builtinId(builtin: BuiltinVariable): Int = when (builtin) {
        BuiltinVariable.Position -> Spv.BuiltInPosition
        BuiltinVariable.PointSize -> Spv.BuiltInPointSize
        BuiltinVariable.ClipDistance -> {
            capability(Spv.CapabilityClipDistance)
            Spv.BuiltInClipDistance
        }

        BuiltinVariable.CullDistance -> {
            capability(Spv.CapabilityCullDistance)
            Spv.BuiltInCullDistance
        }

        BuiltinVariable.Layer -> {
            layerCapability()
            Spv.BuiltInLayer
        }

        BuiltinVariable.ViewportIndex -> {
            viewportCapability()
            Spv.BuiltInViewportIndex
        }

        BuiltinVariable.LayerIn -> {
            capability(Spv.CapabilityGeometry)
            Spv.BuiltInLayer
        }

        BuiltinVariable.ViewportIndexIn -> {
            capability(Spv.CapabilityMultiViewport)
            Spv.BuiltInViewportIndex
        }

        BuiltinVariable.VertexId -> Spv.BuiltInVertexIndex
        BuiltinVariable.InstanceId -> Spv.BuiltInInstanceIndex
        BuiltinVariable.BaseVertex -> {
            capability(Spv.CapabilityDrawParameters)
            Spv.BuiltInBaseVertex
        }

        BuiltinVariable.BaseInstance -> {
            capability(Spv.CapabilityDrawParameters)
            Spv.BuiltInBaseInstance
        }

        BuiltinVariable.FragCoord -> Spv.BuiltInFragCoord
        BuiltinVariable.FrontFacing -> Spv.BuiltInFrontFacing
        BuiltinVariable.PointCoord -> Spv.BuiltInPointCoord
        BuiltinVariable.SampleId -> {
            capability(Spv.CapabilitySampleRateShading)
            Spv.BuiltInSampleId
        }

        BuiltinVariable.SampleMaskIn, BuiltinVariable.SampleMaskOut -> Spv.BuiltInSampleMask
        BuiltinVariable.FragDepth -> Spv.BuiltInFragDepth
        BuiltinVariable.FragStencil -> {
            extension("SPV_EXT_shader_stencil_export")
            capability(Spv.CapabilityStencilExportEXT)
            Spv.BuiltInFragStencilRefEXT
        }

        BuiltinVariable.PrimitiveId -> {
            capability(Spv.CapabilityGeometry)
            Spv.BuiltInPrimitiveId
        }

        BuiltinVariable.BarycentricCoord -> {
            extension("SPV_KHR_fragment_shader_barycentric")
            capability(Spv.CapabilityFragmentBarycentricKHR)
            Spv.BuiltInBaryCoordKHR
        }

        BuiltinVariable.GlobalInvocationId -> Spv.BuiltInGlobalInvocationId
        BuiltinVariable.LocalInvocationId -> Spv.BuiltInLocalInvocationId
        BuiltinVariable.LocalInvocationIndex -> Spv.BuiltInLocalInvocationIndex
        BuiltinVariable.WorkgroupId -> Spv.BuiltInWorkgroupId
        BuiltinVariable.NumWorkgroups -> Spv.BuiltInNumWorkgroups
        BuiltinVariable.SubgroupLocalInvocationId -> {
            capability(Spv.CapabilityGroupNonUniform)
            Spv.BuiltInSubgroupLocalInvocationId
        }

        BuiltinVariable.SubgroupId -> {
            capability(Spv.CapabilityGroupNonUniform)
            Spv.BuiltInSubgroupId
        }

        BuiltinVariable.SubgroupSize -> {
            capability(Spv.CapabilityGroupNonUniform)
            Spv.BuiltInSubgroupSize
        }

        BuiltinVariable.NumSubgroups -> {
            capability(Spv.CapabilityGroupNonUniform)
            Spv.BuiltInNumSubgroups
        }

        BuiltinVariable.ViewIndex -> {
            capability(Spv.CapabilityMultiView)
            Spv.BuiltInViewIndex
        }
    }

    private fun layerCapability() {
        if (version.atLeast(SpirvVersion.V1_5)) {
            capability(Spv.CapabilityShaderLayer)
        } else {
            extension("SPV_EXT_shader_viewport_index_layer")
            capability(Spv.CapabilityShaderViewportIndexLayerEXT)
        }
    }

    private fun viewportCapability() {
        if (version.atLeast(SpirvVersion.V1_5)) {
            capability(Spv.CapabilityShaderViewportIndex)
        } else {
            extension("SPV_EXT_shader_viewport_index_layer")
            capability(Spv.CapabilityShaderViewportIndexLayerEXT)
        }
    }

    fun synthesizedInput(builtin: BuiltinVariable, type: IrType): Int {
        val existing = globals.values.firstOrNull { it.variable.interfaceInfo?.builtin == builtin }
            ?: synthesizedInputs[builtin]
        val global = existing ?: run {
            val variable = GlobalVariable("builtin.${builtin.name}", type, StorageClass.Input)
            variable.interfaceInfo = gg.sona.msl.ir.InterfaceInfo(true, -1, builtin, name = builtin.name)
            global(variable).also { synthesizedInputs[builtin] = it }
        }
        return instruction(Spv.OpLoad, type(global.variable.valueType), global.id)
    }

    fun chain(value: Value): PointerChain {
        chains[value]?.let { return it }
        return when (value) {
            is GlobalVariable -> {
                val global = global(value)
                PointerChain(global.id, global.prefix, value.valueType, global.storage, false).also { chains[value] = it }
            }

            is Instruction -> {
                error("pointer value %${value.id} (${value.opcode}) cannot be represented with logical addressing")
                PointerChain(0, emptyList(), (value.type as? IrPointer)?.pointee ?: IrVoid, StorageClass.Function, false)
            }

            else -> {
                error("unsupported pointer value $value")
                PointerChain(0, emptyList(), IrVoid, StorageClass.Function, false)
            }
        }
    }

    fun materialize(chain: PointerChain): Int {
        if (chain.indices.isEmpty()) return chain.base
        val operands = IntList()
        operands.add(chain.base)
        for (index in chain.indices) operands.add(index)
        return instruction(Spv.OpAccessChain, pointerType(chain.pointee, chain.storage), operands)
    }

    private fun emitFunction(): Int {
        val function = entry.function
        body = functions
        val voidType = type(IrVoid)
        val functionType = run {
            val key = "fn()"
            types[key] ?: declareType(Spv.OpTypeFunction, voidType).also { types[key] = it }
        }
        val functionId = id()
        name(functionId, entry.name)
        functions.instruction(Spv.OpFunction, voidType, functionId, Spv.FunctionControlNone, functionType)
        for (variable in entry.interfaceVariables) global(variable)
        val cfg = ControlFlowGraph(function)
        val order = ArrayList(cfg.reversePostorder)
        for (block in function.blocks) if (block !in order) order.add(block)
        for (block in order) blocks[block] = id()
        var first = true
        for (block in order) {
            functions.instruction(Spv.OpLabel, blocks.getValue(block))
            if (first) {
                for (instruction in function.entry.instructions) {
                    if (instruction.opcode == Opcode.Variable) emitVariable(instruction)
                }
                first = false
            }
            for (instruction in block.instructions) {
                if (instruction.opcode == Opcode.Variable) continue
                if (instruction.isTerminator) emitMerge(block)
                emitInstruction(instruction)
            }
            if (!block.isTerminated) statement(Spv.OpUnreachable)
        }
        functions.instruction(Spv.OpFunctionEnd)
        for ((position, value) in pendingPhiOperands) {
            val resolved = values[value]
            if (resolved == null) error("phi operand %${value.id} (${value.opcode}) has no definition") else functions.words[position] = resolved
        }
        return functionId
    }

    private fun emitVariable(instruction: Instruction) {
        val pointee = (instruction.type as IrPointer).pointee
        val result = id()
        functions.instruction(Spv.OpVariable, pointerType(pointee, StorageClass.Function), result, Spv.StorageClassFunction)
        instruction.name?.let { name(result, it) }
        chains[instruction] = PointerChain(result, emptyList(), pointee, StorageClass.Function, false)
    }

    private fun emitMerge(block: Block) {
        when (block.construct) {
            ConstructKind.Selection -> {
                val terminator = block.terminator!!
                if (terminator.opcode == Opcode.CondBranch || terminator.opcode == Opcode.Switch) {
                    statement(Spv.OpSelectionMerge, blocks.getValue(block.merge!!), Spv.SelectionControlNone)
                }
            }

            ConstructKind.Loop -> statement(
                Spv.OpLoopMerge,
                blocks.getValue(block.merge!!),
                blocks.getValue(block.continueTarget!!),
                Spv.LoopControlNone,
            )

            ConstructKind.None -> Unit
        }
    }

    fun define(instruction: Instruction, id: Int) {
        values[instruction] = id
    }

    fun operand(value: Value): Int = values[value] ?: value(value)

    private fun emitInstruction(instruction: Instruction) {
        val opcode = instruction.opcode
        val resultScalar = instruction.type.let { if (it is IrVector) it.element else it }
        if ((opcode == Opcode.UConvert || opcode == Opcode.FToU) && resultScalar is IrInt && resultScalar.signed) {
            val unsigned = if (instruction.type is IrVector) {
                IrVector.of(resultScalar.withSignedness(false), (instruction.type as IrVector).count)
            } else {
                resultScalar.withSignedness(false)
            }
            val converted = instruction(SIMPLE.getValue(opcode), type(unsigned), operand(instruction.operands[0]))
            define(instruction, instruction(Spv.OpBitcast, type(instruction.type), converted))
            return
        }
        val simple = SIMPLE[opcode]
        if (simple != null) {
            define(instruction, instruction(simple, type(instruction.type), *instruction.operands.map { operand(it) }.toIntArray()))
            return
        }
        when (opcode) {
            Opcode.Load -> define(instruction, load(instruction))
            Opcode.Store -> store(instruction)
            Opcode.AccessChain -> {
                val base = chain(instruction.operands[0])
                val indices = base.indices + instruction.operands.drop(1).map { operand(it) }
                val last = instruction.operands.drop(1)
                var pointee = base.pointee
                var arrayStep = false
                for (index in last) {
                    arrayStep = pointee is IrArray || pointee is IrMatrix || pointee is IrVector
                    pointee = gg.sona.msl.ir.IrBuilder.elementType(pointee, index)
                }
                chains[instruction] = PointerChain(base.base, indices, pointee, base.storage, arrayStep)
            }

            Opcode.PtrOffset -> {
                val base = chain(instruction.operands[0])
                val offset = operand(instruction.operands[1])
                if (!base.arrayStep || base.indices.isEmpty()) {
                    error("pointer arithmetic is only supported on pointers into arrays")
                    chains[instruction] = base
                    return
                }
                val lastIndex = base.indices.last()
                val sum = instruction(Spv.OpIAdd, type(IrInt.I32), lastIndex, offset)
                chains[instruction] = PointerChain(base.base, base.indices.dropLast(1) + sum, base.pointee, base.storage, true)
            }

            Opcode.Select -> define(instruction, select(instruction))
            Opcode.CompositeExtract -> define(
                instruction,
                instruction(Spv.OpCompositeExtract, type(instruction.type), operand(instruction.operands[0]), *instruction.literals),
            )

            Opcode.CompositeInsert -> define(
                instruction,
                instruction(
                    Spv.OpCompositeInsert,
                    type(instruction.type),
                    operand(instruction.operands[0]),
                    operand(instruction.operands[1]),
                    *instruction.literals,
                ),
            )

            Opcode.VectorShuffle -> define(
                instruction,
                instruction(
                    Spv.OpVectorShuffle,
                    type(instruction.type),
                    operand(instruction.operands[0]),
                    operand(instruction.operands[1]),
                    *instruction.literals,
                ),
            )

            Opcode.CompositeConstruct -> {
                val type = instruction.type
                val operands = instruction.operands.map { operand(it) }
                define(instruction, instruction(Spv.OpCompositeConstruct, type(type), *operands.toIntArray()))
            }

            Opcode.Phi -> {
                if (instruction.type is IrPointer) error("pointer phi nodes require variable pointers")
                val list = IntList()
                list.add(type(instruction.type))
                val result = id()
                list.add(result)
                val start = body.words.size + 1
                for (i in instruction.operands.indices) {
                    val incoming = instruction.operands[i]
                    val known = values[incoming]
                    if (known == null && incoming is Instruction) {
                        pendingPhiOperands.add(start + list.size to incoming)
                        list.add(0)
                    } else {
                        list.add(known ?: operand(incoming))
                    }
                    list.add(blocks.getValue(instruction.targets[i]))
                }
                body.instruction(Spv.OpPhi, list)
                define(instruction, result)
            }

            Opcode.Intrinsic -> intrinsics.emit(instruction)?.let { define(instruction, it) }
            Opcode.Branch -> statement(Spv.OpBranch, blocks.getValue(instruction.targets[0]))
            Opcode.CondBranch -> statement(
                Spv.OpBranchConditional,
                operand(instruction.operands[0]),
                blocks.getValue(instruction.targets[0]),
                blocks.getValue(instruction.targets[1]),
            )

            Opcode.Switch -> {
                val operands = IntList()
                operands.add(operand(instruction.operands[0]))
                operands.add(blocks.getValue(instruction.targets[0]))
                instruction.literals.forEachIndexed { index, literal ->
                    operands.add(literal)
                    operands.add(blocks.getValue(instruction.targets[index + 1]))
                }
                body.instruction(Spv.OpSwitch, operands)
            }

            Opcode.Return -> statement(Spv.OpReturn)
            Opcode.Unreachable -> statement(Spv.OpUnreachable)
            Opcode.Variable -> Unit
            else -> error("unsupported instruction ${instruction.opcode}")
        }
    }

    private val pendingPhiOperands = ArrayList<Pair<Int, Instruction>>()

    private fun select(instruction: Instruction): Int {
        val condition = instruction.operands[0]
        val type = instruction.type
        var conditionId = operand(condition)
        if (condition.type is IrBool && type is IrVector && !version.atLeast(SpirvVersion.V1_4)) {
            val vector = IrVector.of(IrBool, type.count)
            conditionId = instruction(Spv.OpCompositeConstruct, type(vector), *IntArray(type.count) { conditionId })
        }
        return instruction(Spv.OpSelect, type(type), conditionId, operand(instruction.operands[1]), operand(instruction.operands[2]))
    }

    private fun load(instruction: Instruction): Int {
        val pointerValue = instruction.operands[0]
        val chain = chain(pointerValue)
        val pointer = materialize(chain)
        val type = instruction.type
        if (chain.storage.isExplicitlyLaidOut && (type is IrStruct || type is IrArray || containsBool(type))) {
            val loaded = instruction(Spv.OpLoad, type(type, SpirvLayout.Explicit), pointer)
            return convertLayout(loaded, type, SpirvLayout.Explicit, SpirvLayout.Logical)
        }
        val loaded = instruction(Spv.OpLoad, type(type), pointer)
        val global = pointerValue as? GlobalVariable
        val builtin = global?.interfaceInfo?.builtin
        return loaded.also { if (builtin != null && global.valueType != type) error("builtin type mismatch") }
    }

    private fun store(instruction: Instruction) {
        val pointerValue = instruction.operands[0]
        val chain = chain(pointerValue)
        val value = instruction.operands[1]
        var valueId = operand(value)
        val type = value.type
        val global = pointerValue as? GlobalVariable
        if (options.flipVertexY && entry.stage == ShaderStage.Vertex && global?.interfaceInfo?.builtin == BuiltinVariable.Position) {
            val flip = constant(
                ConstantComposite(
                    VEC4,
                    listOf(ConstantScalar.f32(1f), ConstantScalar.f32(-1f), ConstantScalar.f32(1f), ConstantScalar.f32(1f)),
                ),
            )
            valueId = instruction(Spv.OpFMul, type(VEC4), valueId, flip)
        }
        if (chain.storage.isExplicitlyLaidOut && (type is IrStruct || type is IrArray || containsBool(type))) {
            valueId = convertLayout(valueId, type, SpirvLayout.Logical, SpirvLayout.Explicit)
        }
        statement(Spv.OpStore, materialize(chain), valueId)
    }

    private fun containsBool(type: IrType): Boolean = when (type) {
        is IrBool -> true
        is IrVector -> type.element is IrBool
        is IrArray -> containsBool(type.element)
        is IrStruct -> type.members.any { containsBool(it.type) }
        else -> false
    }

    private fun convertLayout(value: Int, type: IrType, from: SpirvLayout, to: SpirvLayout): Int {
        if (type is IrBool || type is IrVector && type.element is IrBool) {
            return if (to == SpirvLayout.Logical) {
                instruction(Spv.OpINotEqual, type(type), value, constant(Constants.splat(layoutInteger(type), ConstantScalar.int(IrInt.U8, 0L))))
            } else {
                val integer = layoutInteger(type)
                instruction(
                    Spv.OpSelect,
                    type(integer),
                    value,
                    constant(Constants.splat(integer, ConstantScalar.int(IrInt.U8, 1L))),
                    constant(Constants.splat(integer, ConstantScalar.int(IrInt.U8, 0L))),
                )
            }
        }
        if (type !is IrStruct && type !is IrArray) return value
        if (version.atLeast(SpirvVersion.V1_4) && !containsBool(type)) return instruction(Spv.OpCopyLogical, type(type, to), value)
        return when (type) {
            is IrStruct -> {
                val members = type.members.mapIndexed { index, member ->
                    val extracted = instruction(Spv.OpCompositeExtract, type(member.type, from), value, index)
                    convertLayout(extracted, member.type, from, to)
                }
                instruction(Spv.OpCompositeConstruct, type(type, to), *members.toIntArray())
            }

            is IrArray -> {
                if (type.isRuntime) {
                    error("runtime arrays cannot be copied by value")
                    return value
                }
                val elements = List(type.length) { index ->
                    val extracted = instruction(Spv.OpCompositeExtract, type(type.element, from), value, index)
                    convertLayout(extracted, type.element, from, to)
                }
                instruction(Spv.OpCompositeConstruct, type(type, to), *elements.toIntArray())
            }

            else -> value
        }
    }

    private fun layoutInteger(type: IrType): IrType = if (type is IrVector) IrVector.of(IrInt.U8, type.count) else IrInt.U8

    private companion object {
        val UINT3 = IrVector.of(IrInt.U32, 3)
        val VEC4 = IrVector.of(IrFloat.F32, 4)

        val SIMPLE = mapOf(
            Opcode.IAdd to Spv.OpIAdd,
            Opcode.ISub to Spv.OpISub,
            Opcode.IMul to Spv.OpIMul,
            Opcode.SDiv to Spv.OpSDiv,
            Opcode.UDiv to Spv.OpUDiv,
            Opcode.SRem to Spv.OpSRem,
            Opcode.URem to Spv.OpUMod,
            Opcode.FAdd to Spv.OpFAdd,
            Opcode.FSub to Spv.OpFSub,
            Opcode.FMul to Spv.OpFMul,
            Opcode.FDiv to Spv.OpFDiv,
            Opcode.FRem to Spv.OpFRem,
            Opcode.FNeg to Spv.OpFNegate,
            Opcode.INeg to Spv.OpSNegate,
            Opcode.And to Spv.OpBitwiseAnd,
            Opcode.Or to Spv.OpBitwiseOr,
            Opcode.Xor to Spv.OpBitwiseXor,
            Opcode.Not to Spv.OpNot,
            Opcode.Shl to Spv.OpShiftLeftLogical,
            Opcode.LShr to Spv.OpShiftRightLogical,
            Opcode.AShr to Spv.OpShiftRightArithmetic,
            Opcode.LogicalAnd to Spv.OpLogicalAnd,
            Opcode.LogicalOr to Spv.OpLogicalOr,
            Opcode.LogicalNot to Spv.OpLogicalNot,
            Opcode.LogicalEqual to Spv.OpLogicalEqual,
            Opcode.LogicalNotEqual to Spv.OpLogicalNotEqual,
            Opcode.IEqual to Spv.OpIEqual,
            Opcode.INotEqual to Spv.OpINotEqual,
            Opcode.SLess to Spv.OpSLessThan,
            Opcode.SLessEqual to Spv.OpSLessThanEqual,
            Opcode.SGreater to Spv.OpSGreaterThan,
            Opcode.SGreaterEqual to Spv.OpSGreaterThanEqual,
            Opcode.ULess to Spv.OpULessThan,
            Opcode.ULessEqual to Spv.OpULessThanEqual,
            Opcode.UGreater to Spv.OpUGreaterThan,
            Opcode.UGreaterEqual to Spv.OpUGreaterThanEqual,
            Opcode.FEqual to Spv.OpFOrdEqual,
            Opcode.FNotEqual to Spv.OpFUnordNotEqual,
            Opcode.FLess to Spv.OpFOrdLessThan,
            Opcode.FLessEqual to Spv.OpFOrdLessThanEqual,
            Opcode.FGreater to Spv.OpFOrdGreaterThan,
            Opcode.FGreaterEqual to Spv.OpFOrdGreaterThanEqual,
            Opcode.SToF to Spv.OpConvertSToF,
            Opcode.UToF to Spv.OpConvertUToF,
            Opcode.FToS to Spv.OpConvertFToS,
            Opcode.FToU to Spv.OpConvertFToU,
            Opcode.FConvert to Spv.OpFConvert,
            Opcode.SConvert to Spv.OpSConvert,
            Opcode.UConvert to Spv.OpUConvert,
            Opcode.Bitcast to Spv.OpBitcast,
            Opcode.VectorExtractDynamic to Spv.OpVectorExtractDynamic,
            Opcode.VectorInsertDynamic to Spv.OpVectorInsertDynamic,
            Opcode.MatrixTimesVector to Spv.OpMatrixTimesVector,
            Opcode.VectorTimesMatrix to Spv.OpVectorTimesMatrix,
            Opcode.MatrixTimesMatrix to Spv.OpMatrixTimesMatrix,
            Opcode.MatrixTimesScalar to Spv.OpMatrixTimesScalar,
            Opcode.VectorTimesScalar to Spv.OpVectorTimesScalar,
        )
    }
}
