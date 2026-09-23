package gg.sona.msl.dxil

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.DepthMode
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.ImageAccess
import gg.sona.msl.ir.ImageDim
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
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
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.ir.SampledKind
import gg.sona.msl.ir.Sampling
import gg.sona.msl.ir.SpecConstant
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.llvm.LlvmAggregate
import gg.sona.msl.llvm.LlvmArrayType
import gg.sona.msl.llvm.LlvmAttribute
import gg.sona.msl.llvm.LlvmBlock
import gg.sona.msl.llvm.LlvmBuilder
import gg.sona.msl.llvm.LlvmConstant
import gg.sona.msl.llvm.LlvmConstantFloat
import gg.sona.msl.llvm.LlvmConstantInt
import gg.sona.msl.llvm.LlvmFloatType
import gg.sona.msl.llvm.LlvmFunction
import gg.sona.msl.llvm.LlvmFunctionType
import gg.sona.msl.llvm.LlvmGlobalVariable
import gg.sona.msl.llvm.LlvmInstruction
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmLinkage
import gg.sona.msl.llvm.LlvmModule
import gg.sona.msl.llvm.LlvmNull
import gg.sona.msl.llvm.LlvmType
import gg.sona.msl.llvm.LlvmUndef
import gg.sona.msl.llvm.LlvmValue
import gg.sona.msl.llvm.LlvmVoidType
import gg.sona.msl.passes.ControlFlowGraph
import gg.sona.msl.reflect.RegisterLocation
import gg.sona.msl.reflect.ResourceBindingRequest
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.util.HalfFloat

class DxilEmitter(
    val module: IrModule,
    val entry: EntryPoint,
    val options: DxilOptions,
    private val diagnostics: Diagnostics,
) {
    val llvm = LlvmModule("dxil-ms-dx", DATA_LAYOUT)
    val types = DxilTypes()
    val builder = LlvmBuilder(llvm)
    val stage: ShaderStage = entry.stage
    val nativeLowPrecision: Boolean = usesSixteenBit()
    val resources = ArrayList<DxilResource>()
    val inputs = ArrayList<DxilSignatureElement>()
    val outputs = ArrayList<DxilSignatureElement>()
    val features = DxilFeatures()
    private val resourceByGlobal = HashMap<GlobalVariable, DxilResource>()
    private val elementByGlobal = HashMap<GlobalVariable, DxilSignatureElement>()
    private val values = HashMap<Value, List<LlvmValue>>()
    private val pointers = HashMap<Value, DxilPointer>()
    private val storages = HashMap<Value, StorageNode>()
    private val blockMap = HashMap<Block, LlvmBlock>()
    private val blockEnds = HashMap<Block, LlvmBlock>()
    private val pendingPhis = ArrayList<Pair<Instruction, List<LlvmInstruction>>>()
    private lateinit var function: LlvmFunction
    private lateinit var entryBlock: LlvmBlock
    private val intrinsics = DxilIntrinsicEmitter(this)
    private var dispatchResource: DxilResource? = null

    val dispatchSizeLocation: RegisterLocation?
        get() = dispatchResource?.let { RegisterLocation(it.space, it.register) }
    var hasErrors = false
        private set

    val shaderModelMinor: Int
        get() = maxOf(options.minimumShaderModel, features.minimumShaderModel, if (nativeLowPrecision) 2 else 0)

    fun error(message: String) {
        hasErrors = true
        diagnostics.error(SourceLocation.NONE, "DXIL: $message")
    }

    private fun usesSixteenBit(): Boolean {
        fun check(type: IrType): Boolean = when (type) {
            is IrScalar -> type.bits == 16
            is IrVector -> check(type.element)
            is IrMatrix -> check(type.column)
            is IrArray -> check(type.element)
            is IrStruct -> type.members.any { check(it.type) }
            is IrPointer -> check(type.pointee)
            else -> false
        }
        return entry.function.instructions().any { check(it.type) || it.operands.any { operand -> check(operand.type) } } ||
            entry.interfaceVariables.any { check(it.valueType) }
    }

    fun emit(): DxilModuleResult {
        function = LlvmFunction(entry.name, LlvmFunctionType(LlvmVoidType, emptyList()))
        llvm.functions.add(function)
        buildSignatures()
        buildResources()
        emitBody()
        features.lowPrecision = function.blocks.any { block ->
            block.instructions.any { instruction -> isLowPrecision(instruction.type) || instruction.operands.any { isLowPrecision(it.type) } }
        }
        val metadata = DxilMetadataBuilder(this).build(function)
        llvm.namedMetadata.putAll(metadata)
        return DxilModuleResult(llvm, function)
    }

    private fun isLowPrecision(type: LlvmType): Boolean =
        type is LlvmFloatType && type.bits == 16 || type is LlvmIntType && type.bits == 16

    fun scalarType(type: IrScalar): LlvmType = when (type) {
        is IrBool -> LlvmIntType.I1
        is IrInt -> when {
            type.bits == 16 -> LlvmIntType.I16
            type.bits == 64 -> {
                features.int64 = true
                LlvmIntType.I64
            }

            else -> LlvmIntType.I32
        }

        is IrFloat -> when (type.bits) {
            16 -> LlvmFloatType.Half
            64 -> {
                features.doubles = true
                LlvmFloatType.Double
            }

            else -> LlvmFloatType.Float
        }
    }

    fun memoryType(type: IrScalar): LlvmType = if (type is IrBool) LlvmIntType.I32 else scalarType(type)

    fun scalars(type: IrType): List<IrScalar> = when (type) {
        is IrScalar -> listOf(type)
        is IrVector -> List(type.count) { type.element }
        is IrMatrix -> List(type.columns * type.rows) { type.column.element }
        is IrArray -> {
            val element = scalars(type.element)
            List(type.length) { element }.flatten()
        }

        is IrStruct -> type.members.flatMap { scalars(it.type) }
        else -> emptyList()
    }

    fun scalarCount(type: IrType): Int = when (type) {
        is IrScalar -> 1
        is IrVector -> type.count
        is IrMatrix -> type.columns * type.rows
        is IrArray -> type.length * scalarCount(type.element)
        is IrStruct -> type.members.sumOf { scalarCount(it.type) }
        else -> 1
    }

    fun i32(value: Int): LlvmConstantInt = LlvmConstantInt(LlvmIntType.I32, value.toLong())

    fun i8(value: Int): LlvmConstantInt = LlvmConstantInt(LlvmIntType.I8, value.toLong())

    fun i1(value: Boolean): LlvmConstantInt = LlvmConstantInt(LlvmIntType.I1, if (value) 1 else 0)

    fun f32(value: Float): LlvmConstantFloat = LlvmConstantFloat(LlvmFloatType.Float, value.toRawBits().toLong() and 0xFFFFFFFFL)

    fun undef(type: LlvmType): LlvmUndef = LlvmUndef(type)

    fun scalarConstant(type: IrScalar, bits: Long): LlvmConstant = when (type) {
        is IrBool -> i1(bits != 0L)
        is IrInt -> LlvmConstantInt(scalarType(type) as LlvmIntType, if (type.bits == 8) bits and 0xFF else bits)
        is IrFloat -> when (type.bits) {
            16 -> LlvmConstantFloat(LlvmFloatType.Half, HalfFloat.fromFloat(Double.fromBits(bits).toFloat()).toLong())
            64 -> LlvmConstantFloat(LlvmFloatType.Double, bits)
            else -> LlvmConstantFloat(LlvmFloatType.Float, Double.fromBits(bits).toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
        }
    }

    fun constantComponents(constant: IrConstant): List<LlvmConstant> = when (constant) {
        is ConstantScalar -> listOf(scalarConstant(constant.type, constant.bits))
        is ConstantComposite -> constant.elements.flatMap { constantComponents(it) }
        is ConstantNull -> scalars(constant.type).map { scalarConstant(it, 0) }
        is Undef -> scalars(constant.type).map { LlvmUndef(scalarType(it)) }
    }

    fun dxOp(
        name: String,
        overload: LlvmType?,
        returnType: LlvmType,
        parameters: List<LlvmType>,
        attributes: Set<LlvmAttribute>,
    ): LlvmFunction {
        val fullName = if (overload != null) "dx.op.$name.${DxilTypes.suffix(overload)}" else "dx.op.$name"
        return llvm.declare(fullName, LlvmFunctionType(returnType, listOf(LlvmIntType.I32) + parameters), attributes)
    }

    fun callOp(
        name: String,
        opcode: Int,
        overload: LlvmType?,
        returnType: LlvmType,
        arguments: List<LlvmValue>,
        attributes: Set<LlvmAttribute> = READ_NONE,
    ): LlvmValue {
        val function = dxOp(name, overload, returnType, arguments.map { it.type }, attributes)
        return builder.call(function, listOf(i32(opcode)) + arguments)
    }

    private fun buildSignatures() {
        var inputRow = 0
        var outputRow = 0
        val targetRows = HashSet<Int>()
        for (variable in entry.interfaceVariables) {
            val info = variable.interfaceInfo ?: continue
            if (variable.storage != StorageClass.Input && variable.storage != StorageClass.Output) continue
            val isInput = variable.storage == StorageClass.Input
            val builtin = info.builtin
            val componentType = componentType(variable.valueType)
            val columns = when (val type = variable.valueType) {
                is IrVector -> type.count
                is IrArray -> type.length
                else -> 1
            }
            val element: DxilSignatureElement? = if (builtin != null) {
                systemElement(builtin, isInput, componentType, columns, if (isInput) inputs.size else outputs.size) { row ->
                    if (isInput) inputRow.also { inputRow += row } else outputRow.also { outputRow += row }
                }
            } else {
                val vertexInput = isInput && stage == ShaderStage.Vertex
                val (name, index) = options.semantics.semantic(info.location, vertexInput, info.name)
                val row: Int
                if (!isInput && stage == ShaderStage.Fragment) {
                    row = info.location
                    targetRows.add(row)
                } else if (isInput) {
                    row = inputRow++
                } else {
                    row = outputRow++
                }
                val interpolation = when {
                    stage == ShaderStage.Vertex && isInput -> 0
                    stage == ShaderStage.Fragment && !isInput -> 0
                    componentType == COMPONENT_I32 || componentType == COMPONENT_U32 ||
                        componentType == COMPONENT_I16 || componentType == COMPONENT_U16 -> 1

                    else -> interpolationMode(info.interpolation, info.sampling)
                }
                if (isInput && stage == ShaderStage.Fragment && info.sampling == Sampling.Sample) features.sampleFrequency = true
                DxilSignatureElement(
                    if (isInput) inputs.size else outputs.size,
                    if (!isInput && stage == ShaderStage.Fragment) "SV_Target" else name,
                    if (!isInput && stage == ShaderStage.Fragment) info.location else index,
                    componentType,
                    if (!isInput && stage == ShaderStage.Fragment) KIND_TARGET else 0,
                    interpolation,
                    columns,
                    row,
                    0,
                    isInput,
                    isSystemValue = !isInput && stage == ShaderStage.Fragment,
                )
            }
            if (element != null) {
                elementByGlobal[variable] = element
                if (isInput) inputs.add(element) else outputs.add(element)
            }
        }
        if (stage == ShaderStage.Fragment) {
            var row = (targetRows.maxOrNull() ?: -1) + 1
            val relocated = outputs.map { element ->
                if (element.semanticKind != KIND_TARGET && element.isPacked && element.startRow != element.semanticIndex) {
                    DxilSignatureElement(
                        element.id, element.semanticName, element.semanticIndex, element.componentType, element.semanticKind,
                        element.interpolation, element.columns, row++, element.startColumn, false, element.isSystemValue,
                    )
                } else {
                    element
                }
            }
            if (relocated.zip(outputs).any { it.first !== it.second }) {
                val mapping = outputs.zip(relocated).toMap()
                outputs.clear()
                outputs.addAll(relocated)
                for ((global, element) in elementByGlobal.toList()) mapping[element]?.let { elementByGlobal[global] = it }
            }
        }
    }

    private fun interpolationMode(interpolation: Interpolation, sampling: Sampling): Int = when (interpolation) {
        Interpolation.Flat -> 1
        Interpolation.Perspective -> when (sampling) {
            Sampling.Center -> 2
            Sampling.Centroid -> 3
            Sampling.Sample -> 6
        }

        Interpolation.NoPerspective -> when (sampling) {
            Sampling.Center -> 4
            Sampling.Centroid -> 5
            Sampling.Sample -> 7
        }
    }

    private fun componentType(type: IrType): Int = when (val scalar = if (type is IrArray) type.element.scalar else type.scalar) {
        is IrFloat -> if (scalar.bits == 16) COMPONENT_F16 else COMPONENT_F32
        is IrInt -> when {
            scalar.bits == 16 -> if (scalar.signed) COMPONENT_I16 else COMPONENT_U16
            scalar.signed -> COMPONENT_I32
            else -> COMPONENT_U32
        }

        is IrBool -> COMPONENT_U32
    }

    private fun systemElement(
        builtin: BuiltinVariable,
        isInput: Boolean,
        componentType: Int,
        columns: Int,
        id: Int,
        allocate: (Int) -> Int,
    ): DxilSignatureElement? {
        fun packed(name: String, kind: Int, type: Int, interpolation: Int, cols: Int = columns): DxilSignatureElement =
            DxilSignatureElement(id, name, 0, type, kind, interpolation, cols, allocate(1), 0, isInput, true)

        fun unpacked(name: String, kind: Int, type: Int): DxilSignatureElement =
            DxilSignatureElement(id, name, 0, type, kind, 0, 1, -1, -1, isInput, true)

        return when (builtin) {
            BuiltinVariable.VertexId -> packed("SV_VertexID", 1, COMPONENT_U32, 0)
            BuiltinVariable.InstanceId -> packed("SV_InstanceID", 2, COMPONENT_U32, 0)
            BuiltinVariable.Position -> packed("SV_Position", 3, COMPONENT_F32, 4)
            BuiltinVariable.FragCoord -> packed("SV_Position", 3, COMPONENT_F32, 4)
            BuiltinVariable.ClipDistance -> packed("SV_ClipDistance", 6, COMPONENT_F32, 2)
            BuiltinVariable.CullDistance -> packed("SV_CullDistance", 7, COMPONENT_F32, 2)
            BuiltinVariable.Layer, BuiltinVariable.LayerIn -> {
                if (!isInput) features.viewportAndLayer = true
                packed("SV_RenderTargetArrayIndex", 4, COMPONENT_U32, if (isInput || stage == ShaderStage.Vertex) 1 else 0)
            }

            BuiltinVariable.ViewportIndex, BuiltinVariable.ViewportIndexIn -> {
                if (!isInput) features.viewportAndLayer = true
                packed("SV_ViewportArrayIndex", 5, COMPONENT_U32, 1)
            }

            BuiltinVariable.FrontFacing -> packed("SV_IsFrontFace", 13, COMPONENT_U32, 1)
            BuiltinVariable.PrimitiveId -> packed("SV_PrimitiveID", 10, COMPONENT_U32, 1)
            BuiltinVariable.BarycentricCoord -> {
                features.barycentrics = true
                features.require(1)
                packed("SV_Barycentrics", 28, COMPONENT_F32, 2)
            }

            BuiltinVariable.FragDepth -> when (entry.depthMode) {
                DepthMode.Greater -> unpacked("SV_DepthGreaterEqual", 19, COMPONENT_F32)
                DepthMode.Less -> unpacked("SV_DepthLessEqual", 18, COMPONENT_F32)
                else -> unpacked("SV_Depth", 17, COMPONENT_F32)
            }

            BuiltinVariable.FragStencil -> {
                features.stencilRef = true
                unpacked("SV_StencilRef", 20, COMPONENT_U32)
            }

            BuiltinVariable.SampleMaskOut -> unpacked("SV_Coverage", 14, COMPONENT_U32)
            BuiltinVariable.PointSize -> null
            BuiltinVariable.PointCoord -> {
                error("[[point_coord]] has no Direct3D equivalent")
                null
            }

            BuiltinVariable.BaseVertex, BuiltinVariable.BaseInstance -> {
                error("[[base_vertex]] and [[base_instance]] are not available in Direct3D shader model 6.0")
                null
            }

            BuiltinVariable.SampleId -> {
                features.sampleFrequency = true
                null
            }

            else -> null
        }
    }

    private fun buildResources() {
        val globals = entry.interfaceVariables.filter { it.resource != null } +
            module.globals.filter { it.resource?.isConstexprSampler == true && it !in entry.interfaceVariables && usedByEntry(it) }
        val constexpr = module.globals.filter { it.resource?.isConstexprSampler == true }
        for (variable in globals.distinct()) {
            val info = variable.resource!!
            val index = if (info.isConstexprSampler) DxilBindingLayout.CONSTEXPR_SAMPLER_BASE + constexpr.indexOf(variable) else info.mslIndex
            val location = options.bindings.locate(ResourceBindingRequest(variable.name, info.kind, index, info.isConstexprSampler, info.argumentBuffer))
            info.space = location.space
            info.register = location.register
            val (resourceClass, kind, element) = classify(variable)
            val size = when (val type = variable.valueType) {
                is IrStruct -> type.size
                is IrArray -> if (type.isRuntime) 0 else type.length * type.stride
                else -> 0
            }
            val resource = DxilResource(
                variable,
                resourceClass,
                kind,
                location.space,
                location.register,
                info.arraySize,
                element,
                if (resourceClass == DxilResourceClass.CBuffer) maxOf(size, 16) else size,
            )
            if (kind == DxilResourceKind.RAW_BUFFER) features.rawBuffers = true
            if (resourceClass == DxilResourceClass.Uav && stage != ShaderStage.Fragment && stage != ShaderStage.Kernel) {
                features.uavsAtEveryStage = true
            }
            resources.add(resource)
            resourceByGlobal[variable] = resource
        }
        markComparisonSamplers()
        for (resourceClass in DxilResourceClass.entries) {
            resources.filter { it.resourceClass == resourceClass }.forEachIndexed { id, resource -> resource.id = id }
        }
    }

    private fun usedByEntry(variable: GlobalVariable): Boolean =
        entry.function.instructions().any { instruction -> instruction.operands.any { it === variable } }

    private fun markComparisonSamplers() {
        val regular = HashSet<DxilResource>()
        for (instruction in entry.function.instructions()) {
            val intrinsic = instruction.intrinsic ?: continue
            if (intrinsic !in SAMPLING) continue
            val resource = rootGlobal(instruction.operands[1])?.let { resourceByGlobal[it] } ?: continue
            if (intrinsic == Intrinsic.TextureSampleCompare || intrinsic == Intrinsic.TextureGatherCompare) {
                resource.comparison = true
            } else {
                regular.add(resource)
            }
        }
        for (resource in regular) {
            if (resource.comparison) error("sampler '${resource.variable.name}' is used for both comparison and regular sampling, which Direct3D does not allow")
        }
    }

    private fun rootGlobal(value: Value): GlobalVariable? = when (value) {
        is GlobalVariable -> value
        is Instruction -> if (value.opcode == Opcode.Load || value.opcode == Opcode.AccessChain) rootGlobal(value.operands[0]) else null
        else -> null
    }

    private fun classify(variable: GlobalVariable): Triple<DxilResourceClass, Int, Int> {
        val info = variable.resource!!
        return when (info.kind) {
            ResourceKind.UniformBuffer -> Triple(DxilResourceClass.CBuffer, DxilResourceKind.CBUFFER, 0)
            ResourceKind.StorageBuffer -> Triple(
                if (info.readOnly) DxilResourceClass.Srv else DxilResourceClass.Uav,
                DxilResourceKind.RAW_BUFFER,
                0,
            )

            ResourceKind.Sampler -> Triple(DxilResourceClass.Sampler, DxilResourceKind.SAMPLER, 0)
            ResourceKind.SampledTexture, ResourceKind.StorageTexture -> {
                val image = (variable.valueType as? IrImage) ?: ((variable.valueType as IrArray).element as IrImage)
                val kind = when (image.dim) {
                    ImageDim.Dim1D -> if (image.arrayed) DxilResourceKind.TEXTURE_1D_ARRAY else DxilResourceKind.TEXTURE_1D
                    ImageDim.Dim2D -> when {
                        image.multisampled && image.arrayed -> DxilResourceKind.TEXTURE_2D_MS_ARRAY
                        image.multisampled -> DxilResourceKind.TEXTURE_2D_MS
                        image.arrayed -> DxilResourceKind.TEXTURE_2D_ARRAY
                        else -> DxilResourceKind.TEXTURE_2D
                    }

                    ImageDim.Dim3D -> DxilResourceKind.TEXTURE_3D
                    ImageDim.Cube -> if (image.arrayed) DxilResourceKind.TEXTURE_CUBE_ARRAY else DxilResourceKind.TEXTURE_CUBE
                    ImageDim.Buffer -> DxilResourceKind.TYPED_BUFFER
                }
                val element = when (image.sampled) {
                    SampledKind.Float, SampledKind.Half -> COMPONENT_F32
                    SampledKind.SInt, SampledKind.SShort -> COMPONENT_I32
                    SampledKind.UInt, SampledKind.UShort -> COMPONENT_U32
                }
                val storage = image.access == ImageAccess.Write || image.access == ImageAccess.ReadWrite
                Triple(if (storage) DxilResourceClass.Uav else DxilResourceClass.Srv, kind, element)
            }
        }
    }

    val constantRows = HashMap<List<Any>, LlvmValue>()

    fun handle(resource: DxilResource, index: LlvmValue?): LlvmValue {
        if (index == null) {
            resource.handle?.let { return it }
            val saved = builder.block
            builder.position(entryBlock)
            val created = createHandle(resource, i32(resource.register), false, atStart = true)
            builder.position(saved)
            resource.handle = created
            return created
        }
        val constant = index as? LlvmConstantInt
        val slot = if (constant != null) {
            i32(resource.register + constant.value.toInt())
        } else {
            builder.binary(LlvmBuilder.BINOP_ADD, index, i32(resource.register))
        }
        return createHandle(resource, slot, constant == null, atStart = false)
    }

    private fun createHandle(resource: DxilResource, slot: LlvmValue, nonUniform: Boolean, atStart: Boolean): LlvmValue {
        val function = dxOp(
            "createHandle",
            null,
            types.handle,
            listOf(LlvmIntType.I8, LlvmIntType.I32, LlvmIntType.I32, LlvmIntType.I1),
            READ_ONLY,
        )
        val call = LlvmInstruction(
            gg.sona.msl.llvm.LlvmOpcode.Call,
            types.handle,
            mutableListOf(i32(DxilOpcode.CreateHandle), i8(resource.resourceClass.code), i32(resource.id), slot, i1(nonUniform)),
        )
        call.callee = function
        if (atStart) {
            val index = entryBlock.instructions.indexOfFirst { it.opcode != gg.sona.msl.llvm.LlvmOpcode.Alloca && !isHandleCall(it) }
                .let { if (it < 0) entryBlock.instructions.size else it }
            entryBlock.instructions.add(index, call)
        } else {
            builder.block.add(call)
        }
        return call
    }

    private fun isHandleCall(instruction: LlvmInstruction): Boolean = instruction.callee?.name == "dx.op.createHandle"

    fun dispatchSize(): LlvmValue {
        val resource = dispatchResource ?: run {
            val variable = GlobalVariable("dispatch.size", IrVector.of(IrInt.U32, 3), StorageClass.Uniform)
            val created = DxilResource(
                variable,
                DxilResourceClass.CBuffer,
                DxilResourceKind.CBUFFER,
                options.dispatchSizeSpace,
                options.dispatchSizeRegister,
                1,
                0,
                16,
            )
            created.id = resources.count { it.resourceClass == DxilResourceClass.CBuffer }
            resources.add(created)
            dispatchResource = created
            created
        }
        val handle = handle(resource, null)
        return callOp(
            "cbufferLoadLegacy",
            DxilOpcode.CBufferLoadLegacy,
            LlvmIntType.I32,
            types.cbufRet(LlvmIntType.I32),
            listOf(handle, i32(0)),
            READ_ONLY,
        )
    }

    private fun emitBody() {
        val irFunction = entry.function
        val cfg = ControlFlowGraph(irFunction)
        val order = cfg.reversePostorder
        for (block in order) blockMap[block] = function.block()
        entryBlock = blockMap.getValue(order.first())
        for (instruction in irFunction.entry.instructions) {
            if (instruction.opcode == Opcode.Variable) {
                builder.position(entryBlock)
                storages[instruction] = allocateStorage((instruction.type as IrPointer).pointee, local = true, addressSpace = 0, name = "")
                pointers[instruction] = LocalPointer(storages.getValue(instruction), emptyList(), (instruction.type as IrPointer).pointee)
            }
        }
        for (block in order) {
            builder.position(blockMap.getValue(block))
            for (instruction in block.instructions) {
                if (instruction.opcode == Opcode.Variable) continue
                emitInstruction(instruction, cfg)
            }
            blockEnds[block] = builder.block
        }
        for ((phi, llvmPhis) in pendingPhis) {
            for (i in phi.operands.indices) {
                val source = phi.targets[i]
                val end = blockEnds[source] ?: continue
                val components = components(phi.operands[i])
                llvmPhis.forEachIndexed { index, llvmPhi ->
                    llvmPhi.operands.add(components[index])
                    llvmPhi.targets.add(end)
                }
            }
        }
    }

    fun allocateStorage(type: IrType, local: Boolean, addressSpace: Int, name: String, initializer: IrConstant? = null): StorageNode {
        val leaves = ArrayList<StorageLeaf>()
        val root = buildNode(type, emptyList(), 0, leaves)
        val initialValues = HashMap<StorageLeaf, MutableList<LlvmConstant>>()
        if (initializer != null) {
            for (leaf in leaves) initialValues[leaf] = MutableList(leaf.arrayType.count) { LlvmNull(leaf.arrayType.element) }
            fillInitializer(root, initializer, emptyList(), initialValues)
        }
        for (leaf in leaves) {
            if (local) {
                leaf.pointer = builder.alloca(leaf.arrayType, 4, entryBlock)
                continue
            }
            val values = initialValues[leaf]
            val global = LlvmGlobalVariable(
                (if (addressSpace == 3) "g" else "c") + llvm.globals.size + if (options.emitNames && name.isNotEmpty()) ".$name" else "",
                leaf.arrayType,
                addressSpace,
                values != null,
                values?.let { LlvmAggregate(leaf.arrayType, it) },
                if (values != null) LlvmLinkage.Internal else LlvmLinkage.External,
                4,
            )
            llvm.globals.add(global)
            leaf.pointer = global
        }
        return root
    }

    private fun fillInitializer(
        node: StorageNode,
        constant: IrConstant,
        indices: List<Int>,
        values: HashMap<StorageLeaf, MutableList<LlvmConstant>>,
    ) {
        val leaf = node.leaf
        if (leaf != null) {
            var linear = 0
            indices.forEachIndexed { index, value -> linear += value * leaf.strides[index] }
            val component = constantComponents(constant).first()
            values.getValue(leaf)[linear] = if (leaf.scalar is IrBool) {
                i32(if ((component as? LlvmConstantInt)?.value == 0L) 0 else 1)
            } else {
                component
            }
            return
        }
        if (node.members != null) {
            node.members.forEachIndexed { index, member -> fillInitializer(member, elementConstant(constant, node.type, index), indices, values) }
            return
        }
        for (i in 0 until node.length) fillInitializer(node.element!!, elementConstant(constant, node.type, i), indices + i, values)
    }

    private fun elementConstant(constant: IrConstant, type: IrType, index: Int): IrConstant = when (constant) {
        is ConstantComposite -> constant.elements[index]
        is Undef -> Undef(gg.sona.msl.ir.IrBuilder.memberType(type, index))
        else -> Constants.zero(gg.sona.msl.ir.IrBuilder.memberType(type, index))
    }

    private fun buildNode(type: IrType, dimensions: List<Int>, depth: Int, leaves: MutableList<StorageLeaf>): StorageNode = when (type) {
        is IrScalar -> {
            val count = dimensions.fold(1) { a, b -> a * b }.coerceAtLeast(1)
            val leaf = StorageLeaf(type, dimensions, LlvmArrayType(memoryType(type), count))
            leaves.add(leaf)
            StorageNode(type, depth, leaf = leaf)
        }

        is IrVector -> StorageNode(type, depth, element = buildNode(type.element, dimensions + type.count, depth + 1, leaves), length = type.count)
        is IrMatrix -> StorageNode(type, depth, element = buildNode(type.column, dimensions + type.columns, depth + 1, leaves), length = type.columns)
        is IrArray -> StorageNode(type, depth, element = buildNode(type.element, dimensions + type.length, depth + 1, leaves), length = type.length)
        is IrStruct -> StorageNode(type, depth, members = type.members.map { buildNode(it.type, dimensions, depth, leaves) })
        else -> {
            error("type $type cannot be stored in shader memory")
            StorageNode(type, depth)
        }
    }

    fun components(value: Value): List<LlvmValue> {
        values[value]?.let { return it }
        return when (value) {
            is IrConstant -> constantComponents(value)
            is SpecConstant -> constantComponents(value.default)
            else -> {
                error("value $value used before definition")
                scalars(value.type).map { LlvmUndef(scalarType(it)) }
            }
        }
    }

    fun scalar(value: Value): LlvmValue = components(value).first()

    fun define(instruction: Instruction, components: List<LlvmValue>) {
        values[instruction] = components
    }

    fun pointer(value: Value): DxilPointer {
        pointers[value]?.let { return it }
        if (value is GlobalVariable) {
            val pointer = globalPointer(value)
            pointers[value] = pointer
            return pointer
        }
        error("unsupported pointer value $value")
        return LocalPointer(StorageNode(IrVoid, 0), emptyList(), IrVoid)
    }

    private fun globalPointer(variable: GlobalVariable): DxilPointer {
        resourceByGlobal[variable]?.let { resource ->
            return when (resource.resourceClass) {
                DxilResourceClass.CBuffer -> BufferPointer(resource, i32(0), 0, variable.valueType, 0)
                DxilResourceClass.Srv, DxilResourceClass.Uav -> if (resource.kind == DxilResourceKind.RAW_BUFFER) {
                    val stride = (variable.valueType as? IrArray)?.stride ?: 0
                    BufferPointer(resource, i32(0), 0, variable.valueType, stride)
                } else {
                    ResourcePointer(resource, null, variable.valueType)
                }

                DxilResourceClass.Sampler -> ResourcePointer(resource, null, variable.valueType)
            }
        }
        if (variable.storage == StorageClass.Input || variable.storage == StorageClass.Output) {
            return InterfacePointer(variable, 0, variable.valueType)
        }
        if (variable.storage == StorageClass.Workgroup || variable.storage == StorageClass.Private) {
            val saved = builder.block
            builder.position(entryBlock)
            val storage = allocateStorage(
                variable.valueType,
                local = false,
                addressSpace = if (variable.storage == StorageClass.Workgroup) 3 else 0,
                name = variable.name.filter { it.isLetterOrDigit() },
                initializer = variable.initializer.takeIf { variable.storage == StorageClass.Private },
            )
            builder.position(saved)
            return LocalPointer(storage, emptyList(), variable.valueType)
        }
        error("global '${variable.name}' of storage ${variable.storage} is not supported")
        return LocalPointer(StorageNode(IrVoid, 0), emptyList(), IrVoid)
    }

    fun elementType(type: IrType, index: Int): IrType = gg.sona.msl.ir.IrBuilder.memberType(type, index)

    fun addConstant(left: LlvmValue, right: Int): LlvmValue {
        if (right == 0) return left
        if (left is LlvmConstantInt) return i32((left.value + right).toInt())
        return builder.binary(LlvmBuilder.BINOP_ADD, left, i32(right))
    }

    fun multiplyAdd(base: LlvmValue, index: LlvmValue, scale: Int): LlvmValue {
        val scaled = if (index is LlvmConstantInt) {
            i32((index.value * scale).toInt())
        } else if (scale == 1) {
            index
        } else {
            builder.binary(LlvmBuilder.BINOP_MUL, index, i32(scale))
        }
        if (scaled is LlvmConstantInt && base is LlvmConstantInt) return i32((base.value + scaled.value).toInt())
        if (scaled is LlvmConstantInt && scaled.value == 0L) return base
        if (base is LlvmConstantInt && base.value == 0L) return scaled
        return builder.binary(LlvmBuilder.BINOP_ADD, base, scaled)
    }

    fun index32(value: LlvmValue): LlvmValue {
        val type = value.type as? LlvmIntType ?: return value
        return when {
            type.bits == 32 -> value
            value is LlvmConstantInt -> i32(value.value.toInt())
            type.bits < 32 -> builder.cast(LlvmBuilder.CAST_SEXT, value, LlvmIntType.I32)
            else -> builder.cast(LlvmBuilder.CAST_TRUNC, value, LlvmIntType.I32)
        }
    }

    private fun accessChain(instruction: Instruction) {
        var current = pointer(instruction.operands[0])
        for (operand in instruction.operands.drop(1)) {
            val index = index32(scalar(operand))
            current = step(current, index)
        }
        pointers[instruction] = current
    }

    fun step(pointer: DxilPointer, index: LlvmValue): DxilPointer = when (pointer) {
        is LocalPointer -> {
            val node = pointer.node
            val constant = (index as? LlvmConstantInt)?.value?.toInt()
            if (node.members != null) {
                val member = constant ?: 0.also { error("dynamic struct member index") }
                LocalPointer(node.members[member], pointer.indices, elementType(pointer.type, member))
            } else {
                LocalPointer(node.element!!, pointer.indices + index, elementType(pointer.type, 0))
            }
        }

        is BufferPointer -> {
            val type = pointer.type
            val constant = (index as? LlvmConstantInt)?.value?.toInt()
            when (type) {
                is IrStruct -> {
                    val member = type.members[constant ?: 0]
                    val offset = pointer.constantOffset?.let { it + member.offset }
                    BufferPointer(pointer.resource, addConstant(pointer.offset, member.offset), offset, member.type, 0)
                }

                is IrArray -> {
                    val offset = if (constant != null) pointer.constantOffset?.let { it + constant * type.stride } else null
                    BufferPointer(pointer.resource, multiplyAdd(pointer.offset, index, type.stride), offset, type.element, type.stride)
                }

                is IrMatrix -> {
                    val stride = columnStride(type)
                    val offset = if (constant != null) pointer.constantOffset?.let { it + constant * stride } else null
                    BufferPointer(pointer.resource, multiplyAdd(pointer.offset, index, stride), offset, type.column, stride)
                }

                is IrVector -> {
                    val size = type.element.bits / 8
                    val offset = if (constant != null) pointer.constantOffset?.let { it + constant * size } else null
                    BufferPointer(pointer.resource, multiplyAdd(pointer.offset, index, size), offset, type.element, size)
                }

                else -> {
                    error("cannot index into $type")
                    pointer
                }
            }
        }

        is ResourcePointer -> ResourcePointer(pointer.resource, index, (pointer.type as IrArray).element)
        is InterfacePointer -> {
            val constant = (index as? LlvmConstantInt)?.value?.toInt()
            if (constant == null) error("dynamic indexing of shader interface variables is not supported")
            InterfacePointer(pointer.variable, pointer.component + (constant ?: 0), elementType(pointer.type, 0))
        }
    }

    fun columnStride(type: IrMatrix): Int {
        val lanes = if (type.rows == 3) 4 else type.rows
        return lanes * type.column.element.bits / 8
    }

    private fun pointerOffset(instruction: Instruction) {
        val base = pointer(instruction.operands[0])
        val offset = index32(scalar(instruction.operands[1]))
        pointers[instruction] = when (base) {
            is BufferPointer -> {
                if (base.stride == 0) error("pointer arithmetic on a non-array buffer pointer")
                BufferPointer(base.resource, multiplyAdd(base.offset, offset, base.stride), null, base.type, base.stride)
            }

            is LocalPointer -> {
                if (base.indices.isEmpty()) {
                    error("pointer arithmetic outside an array")
                    base
                } else {
                    val last = base.indices.last()
                    val sum = if (last is LlvmConstantInt && offset is LlvmConstantInt) {
                        i32((last.value + offset.value).toInt())
                    } else {
                        builder.binary(LlvmBuilder.BINOP_ADD, last, offset)
                    }
                    LocalPointer(base.node, base.indices.dropLast(1) + sum, base.type)
                }
            }

            else -> {
                error("pointer arithmetic on unsupported pointer")
                base
            }
        }
    }

    fun leafPointer(leaf: StorageLeaf, indices: List<LlvmValue>): LlvmValue {
        var linear: LlvmValue = i32(0)
        indices.forEachIndexed { index, value -> linear = multiplyAdd(linear, value, leaf.strides[index]) }
        return builder.elementPointer(leaf.pointer, listOf(i32(0), linear))
    }

    fun loadLocal(node: StorageNode, indices: List<LlvmValue>): List<LlvmValue> {
        val leaf = node.leaf
        if (leaf != null) {
            val loaded = builder.load(leafPointer(leaf, indices), 4)
            return listOf(if (leaf.scalar is IrBool) builder.compare(LlvmBuilder.ICMP_NE, loaded, i32(0)) else loaded)
        }
        if (node.members != null) return node.members.flatMap { loadLocal(it, indices) }
        return (0 until node.length).flatMap { loadLocal(node.element!!, indices + i32(it)) }
    }

    fun storeLocal(node: StorageNode, indices: List<LlvmValue>, components: List<LlvmValue>, start: Int = 0): Int {
        val leaf = node.leaf
        if (leaf != null) {
            val value = components[start]
            val stored = if (leaf.scalar is IrBool) builder.cast(LlvmBuilder.CAST_ZEXT, value, LlvmIntType.I32) else value
            builder.store(leafPointer(leaf, indices), stored, 4)
            return start + 1
        }
        var position = start
        if (node.members != null) {
            for (member in node.members) position = storeLocal(member, indices, components, position)
            return position
        }
        for (i in 0 until node.length) position = storeLocal(node.element!!, indices + i32(i), components, position)
        return position
    }

    fun load(pointer: DxilPointer): List<LlvmValue> = when (pointer) {
        is LocalPointer -> loadLocal(pointer.node, pointer.indices)
        is BufferPointer -> DxilBufferAccess(this).load(pointer)
        is ResourcePointer -> listOf(handle(pointer.resource, pointer.index))
        is InterfacePointer -> loadInterface(pointer)
    }

    fun store(pointer: DxilPointer, components: List<LlvmValue>) {
        when (pointer) {
            is LocalPointer -> storeLocal(pointer.node, pointer.indices, components)
            is BufferPointer -> DxilBufferAccess(this).store(pointer, components)
            is InterfacePointer -> storeInterface(pointer, components)
            is ResourcePointer -> error("resources cannot be written")
        }
    }

    private fun loadInterface(pointer: InterfacePointer): List<LlvmValue> {
        val variable = pointer.variable
        val builtin = variable.interfaceInfo?.builtin
        val count = scalarCount(pointer.type)
        val scalarsOfType = scalars(pointer.type)
        if (builtin != null) {
            DxilBuiltins(this).load(builtin, pointer.component, count)?.let { return it }
        }
        val element = elementByGlobal[variable] ?: run {
            error("input '${variable.name}' has no signature element")
            return scalarsOfType.map { LlvmUndef(scalarType(it)) }
        }
        return List(count) { index ->
            val column = pointer.component + index
            element.usedMask = element.usedMask or (1 shl (column + maxOf(element.startColumn, 0)))
            val scalar = scalarsOfType[index]
            val loadType = if (scalar is IrBool) LlvmIntType.I32 else scalarType(scalar)
            val loaded = callOp(
                "loadInput",
                DxilOpcode.LoadInput,
                loadType,
                loadType,
                listOf(i32(element.id), i32(0), i8(column), LlvmUndef(LlvmIntType.I32)),
            )
            if (scalar is IrBool) builder.compare(LlvmBuilder.ICMP_NE, loaded, i32(0)) else loaded
        }
    }

    private fun storeInterface(pointer: InterfacePointer, components: List<LlvmValue>) {
        val variable = pointer.variable
        val element = elementByGlobal[variable] ?: return
        val scalarsOfType = scalars(pointer.type)
        components.forEachIndexed { index, value ->
            val column = pointer.component + index
            element.usedMask = element.usedMask or (1 shl (column + maxOf(element.startColumn, 0)))
            val scalar = scalarsOfType.getOrNull(index)
            val stored = if (scalar is IrBool) builder.cast(LlvmBuilder.CAST_ZEXT, value, LlvmIntType.I32) else value
            callOp(
                "storeOutput",
                DxilOpcode.StoreOutput,
                stored.type,
                LlvmVoidType,
                listOf(i32(element.id), i32(0), i8(column), stored),
                NO_UNWIND,
            )
        }
    }

    private fun emitInstruction(instruction: Instruction, cfg: ControlFlowGraph) {
        val opcode = instruction.opcode
        when (opcode) {
            Opcode.Load -> define(instruction, load(pointer(instruction.operands[0])))
            Opcode.Store -> store(pointer(instruction.operands[0]), components(instruction.operands[1]))
            Opcode.AccessChain -> accessChain(instruction)
            Opcode.PtrOffset -> pointerOffset(instruction)
            Opcode.Phi -> {
                if (instruction.type is IrPointer) {
                    error("pointer phi nodes are not supported")
                    return
                }
                val phis = scalars(instruction.type).map { builder.phi(scalarType(it)) }
                define(instruction, phis)
                pendingPhis.add(instruction to phis)
            }

            Opcode.Branch -> builder.branch(target(instruction.targets[0]))
            Opcode.CondBranch -> builder.conditionalBranch(
                scalar(instruction.operands[0]),
                target(instruction.targets[0]),
                target(instruction.targets[1]),
            )

            Opcode.Switch -> {
                val selector = scalar(instruction.operands[0])
                val cases = instruction.literals.mapIndexed { index, literal ->
                    LlvmConstantInt(selector.type as LlvmIntType, literal.toLong()) to target(instruction.targets[index + 1])
                }
                builder.switch(selector, target(instruction.targets[0]), cases)
            }

            Opcode.Return -> builder.ret()
            Opcode.Unreachable -> builder.unreachable()
            Opcode.Intrinsic -> intrinsics.emit(instruction)?.let { define(instruction, it) }
            else -> define(instruction, DxilArithmetic(this).emit(instruction))
        }
    }

    private fun target(block: Block): LlvmBlock = blockMap[block] ?: run {
        val unreachable = function.block()
        val saved = builder.block
        builder.position(unreachable)
        builder.unreachable()
        builder.position(saved)
        blockMap[block] = unreachable
        unreachable
    }

    companion object {
        const val DATA_LAYOUT = "e-m:e-p:32:32-i1:32-i8:32-i16:32-i32:32-i64:64-f16:32-f32:32-f64:64-n8:16:32:64"

        const val COMPONENT_I16 = 2
        const val COMPONENT_U16 = 3
        const val COMPONENT_I32 = 4
        const val COMPONENT_U32 = 5
        const val COMPONENT_F16 = 8
        const val COMPONENT_F32 = 9
        const val KIND_TARGET = 16

        val READ_NONE = setOf(LlvmAttribute.NoUnwind, LlvmAttribute.ReadNone)
        val READ_ONLY = setOf(LlvmAttribute.NoUnwind, LlvmAttribute.ReadOnly)
        val NO_UNWIND = setOf(LlvmAttribute.NoUnwind)
        val NO_DUPLICATE = setOf(LlvmAttribute.NoDuplicate, LlvmAttribute.NoUnwind)

        private val SAMPLING = setOf(
            Intrinsic.TextureSample,
            Intrinsic.TextureSampleCompare,
            Intrinsic.TextureGather,
            Intrinsic.TextureGatherCompare,
            Intrinsic.TextureCalculateLod,
        )
    }
}
