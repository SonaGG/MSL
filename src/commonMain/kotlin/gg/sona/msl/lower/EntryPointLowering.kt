package gg.sona.msl.lower

import gg.sona.msl.ast.Attribute
import gg.sona.msl.hir.Function
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.DepthMode
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.InterfaceInfo
import gg.sona.msl.ir.Interpolation
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.ResourceInfo
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.ir.Sampling
import gg.sona.msl.ir.SpecConstant
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructField
import gg.sona.msl.types.StructType
import gg.sona.msl.types.TextureAccess
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.TypeLayout
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class EntryPointLowering(private val lowering: Lowering, private val source: Function) {
    private val stage = source.stage!!
    private val function = IrFunction(source.name, IrVoid, emptyList())
    private val entry = EntryPoint(source.name, stage, function)
    private val body = FunctionLowering(lowering, function, source, structuredReturns = false, returnSink = ::writeOutputs)
    private val builder = body.builder
    private val attributes = lowering.attributes
    private val diagnostics = lowering.diagnostics
    private val builtinInputs = HashMap<BuiltinVariable, GlobalVariable>()
    private val outputs = ArrayList<Pair<StructField?, GlobalVariable>>()
    private var workgroupConstants: List<Value>? = null

    fun lower() {
        lowering.module.functions.add(function)
        lowering.module.entryPoints.add(entry)
        configureEntry()
        for (parameter in source.parameters) bindParameter(parameter)
        declareOutputs()
        body.lowerBody(source.body!!)
    }

    private fun error(location: SourceLocation, message: String) = diagnostics.error(location, message)

    private fun configureEntry() {
        if (attributes.has(source.attributes, "early_fragment_tests")) entry.earlyFragmentTests = true
        if (stage == ShaderStage.Kernel) {
            val configured = lowering.options.threadgroupSizes[source.name]
            val size = configured ?: run {
                diagnostics.warning(
                    source.location,
                    "threadgroup size for kernel '${source.name}' is not specified; defaulting to 1x1x1",
                )
                WorkgroupSize(1, 1, 1)
            }
            val specializable = lowering.options.specializableThreadgroupSize && lowering.options.specializeFunctionConstants
            entry.workgroupSize = size.copy(specializable = specializable)
            workgroupConstants = if (specializable) {
                val base = lowering.options.threadgroupSizeSpecIdBase
                listOf(size.x, size.y, size.z).mapIndexed { index, value ->
                    val existing = lowering.module.specConstants.firstOrNull { it.specId == base + index }
                    existing ?: SpecConstant(IrInt.U32, base + index, ConstantScalar.u32(value), "threads_per_threadgroup.${"xyz"[index]}")
                        .also { lowering.module.specConstants.add(it) }
                }
            } else {
                listOf(ConstantScalar.u32(size.x), ConstantScalar.u32(size.y), ConstantScalar.u32(size.z))
            }
        }
    }

    private fun addInterface(variable: GlobalVariable): GlobalVariable {
        lowering.addGlobal(variable)
        entry.interfaceVariables.add(variable)
        return variable
    }

    private fun bindParameter(parameter: LocalVariable) {
        val list = parameter.attributes
        val location = parameter.location
        when {
            attributes.has(list, "stage_in") -> stageIn(parameter)
            attributes.has(list, "buffer") -> buffer(parameter, attributes.integer(attributes.find(list, "buffer")!!) ?: 0)
            attributes.has(list, "texture") -> texture(parameter, attributes.integer(attributes.find(list, "texture")!!) ?: 0)
            attributes.has(list, "sampler") -> sampler(parameter, attributes.integer(attributes.find(list, "sampler")!!) ?: 0)
            attributes.has(list, "threadgroup") -> threadgroup(parameter, attributes.integer(attributes.find(list, "threadgroup")!!) ?: 0)
            else -> {
                val attribute = list.firstOrNull { it.name in INPUT_BUILTINS || it.name in DERIVED_BUILTINS }
                if (attribute == null) {
                    error(location, "entry point parameter '${parameter.name}' has no attribute")
                    return
                }
                val value = builtinValue(attribute.name, parameter.type, location) ?: return
                val local = builder.variable(value.type, parameter.name)
                builder.store(local, value)
                body.bind(parameter, Binding(local, false))
            }
        }
    }

    private fun buffer(parameter: LocalVariable, index: Int) {
        val type = parameter.type
        if (parameter.isReference) {
            val space = parameter.addressSpace
            val uniform = space == AddressSpace.Constant
            if (space != AddressSpace.Constant && space != AddressSpace.Device) {
                error(parameter.location, "buffer references must be in the device or constant address space")
                return
            }
            val variable = GlobalVariable(parameter.name, lowering.types.lower(type), if (uniform) StorageClass.Uniform else StorageClass.StorageBuffer)
            variable.resource = ResourceInfo(
                if (uniform) ResourceKind.UniformBuffer else ResourceKind.StorageBuffer,
                index,
                uniform || parameter.isConst,
            )
            addInterface(variable)
            body.bind(parameter, Binding(variable, false))
            return
        }
        if (type !is PointerType) {
            error(parameter.location, "buffer parameter '${parameter.name}' must be a pointer or reference")
            return
        }
        if (type.addressSpace != AddressSpace.Device && type.addressSpace != AddressSpace.Constant) {
            error(parameter.location, "buffer pointers must be in the device or constant address space")
            return
        }
        val element = lowering.types.lower(type.pointee)
        val array = IrArray(element, 0, TypeLayout.stride(type.pointee))
        val variable = GlobalVariable(parameter.name, array, StorageClass.StorageBuffer)
        variable.resource = ResourceInfo(
            ResourceKind.StorageBuffer,
            index,
            type.addressSpace == AddressSpace.Constant || type.isConstPointee,
        )
        addInterface(variable)
        val pointer = builder.accessChain(variable, listOf(ConstantScalar.i32(0)))
        val local = builder.variable(pointer.type, parameter.name)
        builder.store(local, pointer)
        body.bind(parameter, Binding(local, false))
    }

    private fun texture(parameter: LocalVariable, index: Int) {
        val type = parameter.type
        val texture = when (type) {
            is TextureType -> type
            is ArrayType -> type.element as? TextureType
            else -> null
        }
        if (texture == null) {
            error(parameter.location, "texture parameter '${parameter.name}' must have a texture type")
            return
        }
        val storage = texture.access == TextureAccess.Write || texture.access == TextureAccess.ReadWrite
        val variable = GlobalVariable(parameter.name, lowering.types.lower(type), StorageClass.UniformConstant)
        variable.resource = ResourceInfo(
            if (storage) ResourceKind.StorageTexture else ResourceKind.SampledTexture,
            index,
            !storage,
            arraySize = (type as? ArrayType)?.size ?: 1,
        )
        addInterface(variable)
        if (type is ArrayType) {
            body.bind(parameter, Binding(variable, false))
        } else {
            body.bind(parameter, Binding(builder.load(variable), true))
        }
    }

    private fun sampler(parameter: LocalVariable, index: Int) {
        val type = parameter.type
        if (type != SamplerType && !(type is ArrayType && type.element == SamplerType)) {
            error(parameter.location, "sampler parameter '${parameter.name}' must have sampler type")
            return
        }
        val variable = GlobalVariable(parameter.name, lowering.types.lower(type), StorageClass.UniformConstant)
        variable.resource = ResourceInfo(ResourceKind.Sampler, index, true, arraySize = (type as? ArrayType)?.size ?: 1)
        addInterface(variable)
        if (type is ArrayType) {
            body.bind(parameter, Binding(variable, false))
        } else {
            body.bind(parameter, Binding(builder.load(variable), true))
        }
    }

    private fun threadgroup(parameter: LocalVariable, index: Int) {
        val type = parameter.type
        if (type !is PointerType || type.addressSpace != AddressSpace.Threadgroup) {
            error(parameter.location, "threadgroup parameter '${parameter.name}' must be a threadgroup pointer")
            return
        }
        if (stage != ShaderStage.Kernel) {
            error(parameter.location, "threadgroup parameters are only allowed in kernel functions")
            return
        }
        val variable = lowering.threadgroupArray(parameter.name, type.pointee, index)
        entry.interfaceVariables.add(variable)
        val pointer = builder.accessChain(variable, listOf(ConstantScalar.i32(0)))
        val local = builder.variable(pointer.type, parameter.name)
        builder.store(local, pointer)
        body.bind(parameter, Binding(local, false))
    }

    private fun stageIn(parameter: LocalVariable) {
        val struct = parameter.type as? StructType
        if (struct == null) {
            error(parameter.location, "[[stage_in]] parameter must be a structure")
            return
        }
        if (stage == ShaderStage.Kernel) {
            error(parameter.location, "[[stage_in]] is not supported for kernel functions")
            return
        }
        val locations = varyingLocations(struct)
        val values = ArrayList<Value>()
        for (field in struct.fields) {
            val builtin = field.attributes.firstOrNull { it.name in INPUT_BUILTINS || it.name in DERIVED_BUILTINS }
            if (builtin != null) {
                values.add(builtinValue(builtin.name, field.type, field.location) ?: return)
                continue
            }
            val fieldType = lowering.types.lower(field.type)
            if (stage == ShaderStage.Fragment && isBuiltinField(field)) {
                values.add(Undef(fieldType))
                continue
            }
            if (fieldType.isBool) {
                error(field.location, "boolean stage inputs are not supported")
                return
            }
            val location = if (stage == ShaderStage.Vertex) {
                val attribute = attributes.find(field.attributes, "attribute")
                if (attribute == null) {
                    error(field.location, "vertex input '${field.name}' requires an [[attribute(n)]] qualifier")
                    return
                }
                attributes.integer(attribute) ?: 0
            } else {
                locations.getValue(field)
            }
            val interpolation = if (stage == ShaderStage.Fragment) interpolation(field.attributes, fieldType) else Interpolation.Perspective
            val sampling = if (stage == ShaderStage.Fragment) sampling(field.attributes) else Sampling.Center
            val variable = GlobalVariable("${parameter.name}.${field.name}", fieldType, StorageClass.Input)
            variable.interfaceInfo = InterfaceInfo(true, location, null, interpolation, sampling, name = field.name)
            addInterface(variable)
            values.add(builder.load(variable))
        }
        val value = builder.construct(lowering.types.lower(struct), values)
        val local = builder.variable(value.type, parameter.name)
        builder.store(local, value)
        body.bind(parameter, Binding(local, false))
    }

    private fun interpolation(list: List<Attribute>, type: IrType): Interpolation = when {
        type.scalar is IrInt -> Interpolation.Flat
        attributes.has(list, "flat") -> Interpolation.Flat
        list.any { it.name.endsWith("no_perspective") } -> Interpolation.NoPerspective
        else -> Interpolation.Perspective
    }

    private fun sampling(list: List<Attribute>): Sampling = when {
        list.any { it.name.startsWith("centroid_") } -> Sampling.Centroid
        list.any { it.name.startsWith("sample_") && it.name != "sample_mask" && it.name != "sample_id" } -> Sampling.Sample
        else -> Sampling.Center
    }

    private fun varyingLocations(struct: StructType): Map<StructField, Int> {
        val result = LinkedHashMap<StructField, Int>()
        val used = HashSet<Int>()
        val explicit = HashMap<StructField, Int>()
        for (field in struct.fields) {
            if (isBuiltinField(field)) continue
            val user = attributes.find(field.attributes, "user")?.let { attributes.identifier(it) }
            val match = user?.let { LOCATION_PATTERN.matchEntire(it) }
            if (match != null) {
                val location = match.groupValues[1].toInt()
                explicit[field] = location
                used.add(location)
            }
        }
        var next = 0
        for (field in struct.fields) {
            if (isBuiltinField(field)) continue
            val location = explicit[field] ?: run {
                while (next in used) next++
                used.add(next)
                next
            }
            result[field] = location
        }
        return result
    }

    private fun isBuiltinField(field: StructField): Boolean = field.attributes.any {
        it.name in OUTPUT_BUILTINS || it.name in INPUT_BUILTINS || it.name in DERIVED_BUILTINS
    }

    private fun builtinInput(builtin: BuiltinVariable, type: IrType): GlobalVariable = builtinInputs.getOrPut(builtin) {
        val variable = GlobalVariable("builtin.${builtin.name}", type, StorageClass.Input)
        variable.interfaceInfo = InterfaceInfo(true, -1, builtin, name = builtin.name)
        addInterface(variable)
    }

    private fun builtinValue(name: String, target: Type, location: SourceLocation): Value? {
        val targetType = lowering.types.lower(target)
        val base: Value = when (name) {
            "threads_per_threadgroup", "dispatch_threads_per_threadgroup" -> workgroupSize(location) ?: return null
            "threads_per_grid" -> {
                val groups = builder.load(builtinInput(BuiltinVariable.NumWorkgroups, UINT3))
                val size = workgroupSize(location) ?: return null
                builder.binary(Opcode.IMul, UINT3, groups, size)
            }

            "thread_index_in_quadgroup" -> {
                val lane = builder.load(builtinInput(BuiltinVariable.SubgroupLocalInvocationId, IrInt.U32))
                builder.binary(Opcode.And, IrInt.U32, lane, ConstantScalar.u32(3))
            }

            "quadgroup_index_in_threadgroup" -> {
                val index = builder.load(builtinInput(BuiltinVariable.LocalInvocationIndex, IrInt.U32))
                builder.binary(Opcode.LShr, IrInt.U32, index, ConstantScalar.u32(2))
            }

            "quadgroups_per_threadgroup" -> {
                val size = entry.workgroupSize
                ConstantScalar.u32((size.total + 3) / 4)
            }

            else -> {
                val builtin = INPUT_BUILTINS[name] ?: run {
                    error(location, "unsupported attribute [[$name]]")
                    return null
                }
                if (!allowed(builtin)) {
                    error(location, "[[$name]] is not available in ${stage.keyword} functions")
                    return null
                }
                builder.load(builtinInput(builtin, builtinType(builtin)))
            }
        }
        return adapt(base, targetType, location)
    }

    private fun allowed(builtin: BuiltinVariable): Boolean = when (builtin) {
        BuiltinVariable.VertexId, BuiltinVariable.InstanceId, BuiltinVariable.BaseVertex, BuiltinVariable.BaseInstance ->
            stage == ShaderStage.Vertex

        BuiltinVariable.FragCoord, BuiltinVariable.FrontFacing, BuiltinVariable.PointCoord, BuiltinVariable.SampleId,
        BuiltinVariable.SampleMaskIn, BuiltinVariable.PrimitiveId, BuiltinVariable.LayerIn, BuiltinVariable.ViewportIndexIn,
        BuiltinVariable.BarycentricCoord,
        -> stage == ShaderStage.Fragment

        BuiltinVariable.GlobalInvocationId, BuiltinVariable.LocalInvocationId, BuiltinVariable.LocalInvocationIndex,
        BuiltinVariable.WorkgroupId, BuiltinVariable.NumWorkgroups, BuiltinVariable.SubgroupId, BuiltinVariable.NumSubgroups,
        -> stage == ShaderStage.Kernel

        else -> true
    }

    private fun workgroupSize(location: SourceLocation): Value? {
        val constants = workgroupConstants ?: run {
            error(location, "threadgroup size is only available in kernel functions")
            return null
        }
        if (constants.all { it is ConstantScalar }) return ConstantComposite(UINT3, constants.map { it as ConstantScalar })
        return builder.construct(UINT3, constants)
    }

    private fun builtinType(builtin: BuiltinVariable): IrType = when (builtin) {
        BuiltinVariable.GlobalInvocationId, BuiltinVariable.LocalInvocationId, BuiltinVariable.WorkgroupId,
        BuiltinVariable.NumWorkgroups,
        -> UINT3

        BuiltinVariable.FragCoord -> IrVector.of(IrFloat.F32, 4)
        BuiltinVariable.FrontFacing -> IrBool
        BuiltinVariable.PointCoord -> IrVector.of(IrFloat.F32, 2)
        BuiltinVariable.BarycentricCoord -> IrVector.of(IrFloat.F32, 3)
        else -> IrInt.U32
    }

    private fun adapt(value: Value, target: IrType, location: SourceLocation): Value? {
        var current = value
        val sourceCount = current.type.componentCount
        val targetCount = target.componentCount
        if (sourceCount != targetCount) {
            if (targetCount > sourceCount) {
                error(location, "builtin value has $sourceCount components but '$target' was requested")
                return null
            }
            current = builder.shuffle(current, current, IntArray(targetCount) { it })
        }
        if (current.type == target) return current
        return body.convert(current, target)
    }

    private fun declareOutputs() {
        val returnType = source.returnType
        when (stage) {
            ShaderStage.Kernel -> if (returnType != VoidType) error(source.location, "kernel functions must return void")
            ShaderStage.Vertex -> vertexOutputs(returnType)
            ShaderStage.Fragment -> fragmentOutputs(returnType)
        }
    }

    private fun vertexOutputs(returnType: Type) {
        when (returnType) {
            is VoidType -> Unit
            is VectorType -> {
                if (returnType.size != 4 || !returnType.element.kind.isFloat) {
                    error(source.location, "vertex functions returning a vector must return float4")
                    return
                }
                outputs.add(null to output("position", IrVector.of(IrFloat.F32, 4), BuiltinVariable.Position, -1))
            }

            is StructType -> {
                val locations = varyingLocations(returnType)
                for (field in returnType.fields) {
                    val builtin = field.attributes.firstOrNull { it.name in OUTPUT_BUILTINS }
                    val fieldType = lowering.types.lower(field.type)
                    val variable = when (builtin?.name) {
                        null -> {
                            if (fieldType.isBool) {
                                error(field.location, "boolean vertex outputs are not supported")
                                continue
                            }
                            output(field.name, fieldType, null, locations.getValue(field), field.attributes)
                        }

                        "position" -> output(field.name, IrVector.of(IrFloat.F32, 4), BuiltinVariable.Position, -1, field.attributes)
                        "point_size" -> output(field.name, IrFloat.F32, BuiltinVariable.PointSize, -1)
                        "clip_distance" -> {
                            val count = (field.type as? ArrayType)?.size ?: 1
                            output(field.name, IrArray(IrFloat.F32, count, 4), BuiltinVariable.ClipDistance, -1)
                        }

                        "render_target_array_index" -> output(field.name, IrInt.U32, BuiltinVariable.Layer, -1)
                        "viewport_array_index" -> output(field.name, IrInt.U32, BuiltinVariable.ViewportIndex, -1)
                        else -> {
                            error(field.location, "[[${builtin.name}]] is not a vertex output")
                            continue
                        }
                    }
                    outputs.add(field to variable)
                }
            }

            else -> error(source.location, "unsupported vertex function return type '$returnType'")
        }
    }

    private fun fragmentOutputs(returnType: Type) {
        when (returnType) {
            is VoidType -> Unit
            is ScalarType, is VectorType -> {
                outputs.add(null to output("color0", lowering.types.lower(returnType), null, 0))
            }

            is StructType -> {
                for (field in returnType.fields) {
                    val fieldType = lowering.types.lower(field.type)
                    val color = attributes.find(field.attributes, "color")
                    val depth = attributes.find(field.attributes, "depth")
                    val variable = when {
                        color != null -> {
                            val index = attributes.find(field.attributes, "index")?.let { attributes.integer(it) } ?: 0
                            output(field.name, fieldType, null, attributes.integer(color) ?: 0, index = index)
                        }

                        depth != null -> {
                            entry.depthMode = when (attributes.identifier(depth)) {
                                "greater" -> DepthMode.Greater
                                "less" -> DepthMode.Less
                                else -> DepthMode.Any
                            }
                            output(field.name, IrFloat.F32, BuiltinVariable.FragDepth, -1)
                        }

                        attributes.has(field.attributes, "stencil") -> output(field.name, IrInt.U32, BuiltinVariable.FragStencil, -1)
                        attributes.has(field.attributes, "sample_mask") -> output(field.name, IrInt.U32, BuiltinVariable.SampleMaskOut, -1)
                        else -> {
                            error(field.location, "fragment output '${field.name}' requires [[color(n)]], [[depth]], [[stencil]] or [[sample_mask]]")
                            continue
                        }
                    }
                    outputs.add(field to variable)
                }
            }

            else -> error(source.location, "unsupported fragment function return type '$returnType'")
        }
    }

    private fun output(
        name: String,
        type: IrType,
        builtin: BuiltinVariable?,
        location: Int,
        fieldAttributes: List<Attribute> = emptyList(),
        index: Int = 0,
    ): GlobalVariable {
        val variable = GlobalVariable("out.$name", type, StorageClass.Output)
        variable.interfaceInfo = InterfaceInfo(
            false,
            location,
            builtin,
            index = index,
            name = name,
            invariant = attributes.has(fieldAttributes, "invariant"),
        )
        return addInterface(variable)
    }

    private fun writeOutputs(value: Value?) {
        if (value == null) return
        for ((field, variable) in outputs) {
            val component = if (field == null) value else builder.extract(value, field.index)
            val target = variable.valueType
            val converted = when {
                target is IrArray && component.type !is IrArray -> builder.construct(target, listOf(body.convert(component, target.element)))
                component.type == target -> component
                target is IrScalar || target is IrVector -> body.convert(component, target)
                else -> component
            }
            builder.store(variable, converted)
        }
    }

    private companion object {
        val UINT3 = IrVector.of(IrInt.U32, 3)
        val LOCATION_PATTERN = Regex("locn(\\d+)")

        val INPUT_BUILTINS = mapOf(
            "vertex_id" to BuiltinVariable.VertexId,
            "instance_id" to BuiltinVariable.InstanceId,
            "base_vertex" to BuiltinVariable.BaseVertex,
            "base_instance" to BuiltinVariable.BaseInstance,
            "amplification_id" to BuiltinVariable.ViewIndex,
            "position" to BuiltinVariable.FragCoord,
            "front_facing" to BuiltinVariable.FrontFacing,
            "point_coord" to BuiltinVariable.PointCoord,
            "sample_id" to BuiltinVariable.SampleId,
            "sample_mask" to BuiltinVariable.SampleMaskIn,
            "primitive_id" to BuiltinVariable.PrimitiveId,
            "render_target_array_index" to BuiltinVariable.LayerIn,
            "viewport_array_index" to BuiltinVariable.ViewportIndexIn,
            "barycentric_coord" to BuiltinVariable.BarycentricCoord,
            "thread_position_in_grid" to BuiltinVariable.GlobalInvocationId,
            "thread_position_in_threadgroup" to BuiltinVariable.LocalInvocationId,
            "thread_index_in_threadgroup" to BuiltinVariable.LocalInvocationIndex,
            "threadgroup_position_in_grid" to BuiltinVariable.WorkgroupId,
            "threadgroups_per_grid" to BuiltinVariable.NumWorkgroups,
            "thread_index_in_simdgroup" to BuiltinVariable.SubgroupLocalInvocationId,
            "simdgroup_index_in_threadgroup" to BuiltinVariable.SubgroupId,
            "threads_per_simdgroup" to BuiltinVariable.SubgroupSize,
            "thread_execution_width" to BuiltinVariable.SubgroupSize,
            "simdgroups_per_threadgroup" to BuiltinVariable.NumSubgroups,
            "dispatch_simdgroups_per_threadgroup" to BuiltinVariable.NumSubgroups,
        )

        val DERIVED_BUILTINS = setOf(
            "threads_per_threadgroup",
            "dispatch_threads_per_threadgroup",
            "threads_per_grid",
            "thread_index_in_quadgroup",
            "quadgroup_index_in_threadgroup",
            "quadgroups_per_threadgroup",
        )

        val OUTPUT_BUILTINS = setOf(
            "position",
            "point_size",
            "clip_distance",
            "render_target_array_index",
            "viewport_array_index",
            "depth",
            "stencil",
            "sample_mask",
        )
    }
}
