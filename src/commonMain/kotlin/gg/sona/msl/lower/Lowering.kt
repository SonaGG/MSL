package gg.sona.msl.lower

import gg.sona.msl.hir.Function
import gg.sona.msl.hir.GlobalVariable
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.hir.Program
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.Parameter
import gg.sona.msl.ir.ResourceInfo
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.ir.SpecConstant
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.TypeLayout
import gg.sona.msl.types.VectorType

class Lowering(
    val program: Program,
    val options: LoweringOptions,
    val diagnostics: Diagnostics,
) {
    val module = IrModule()
    val types = TypeLowering(module)
    val attributes = AttributeValues(program)
    private val functions = HashMap<Function, IrFunction>()
    private val inProgress = HashSet<Function>()
    private val globalValues = HashMap<GlobalVariable, Value>()
    private val globalPointers = HashMap<GlobalVariable, gg.sona.msl.ir.GlobalVariable>()
    private val definedFlags = HashMap<GlobalVariable, Value>()
    private val threadgroupLocals = HashMap<LocalVariable, gg.sona.msl.ir.GlobalVariable>()
    private var uniqueCounter = 0

    fun lower(): IrModule {
        val selected = program.entryPoints.filter { options.entryPoints == null || it.name in options.entryPoints }
        if (options.entryPoints != null) {
            for (name in options.entryPoints) {
                if (selected.none { it.name == name }) diagnostics.error(SourceLocation.NONE, "entry point '$name' not found")
            }
        }
        for (entry in selected) EntryPointLowering(this, entry).lower()
        return module
    }

    fun uniqueName(base: String): String = "$base.${uniqueCounter++}"

    fun addGlobal(global: gg.sona.msl.ir.GlobalVariable): gg.sona.msl.ir.GlobalVariable {
        module.globals.add(global)
        return global
    }

    fun functionFor(function: Function, location: SourceLocation): IrFunction? {
        functions[function]?.let { return it }
        if (function in inProgress) {
            diagnostics.error(location, "recursive call to '${function.name}' is not supported")
            return null
        }
        if (function.isEntryPoint) {
            diagnostics.error(location, "entry point '${function.name}' cannot be called")
            return null
        }
        val body = function.body ?: run {
            diagnostics.error(location, "function '${function.name}' is declared but never defined")
            return null
        }
        inProgress.add(function)
        val parameters = function.parameters.map { parameter ->
            val type = if (parameter.isReference) {
                IrPointer(types.lower(parameter.type), types.storageFor(parameter.addressSpace))
            } else {
                types.lower(parameter.type)
            }
            Parameter(type, parameter.name)
        }
        val irFunction = IrFunction(function.mangledName, types.lower(function.returnType), parameters)
        functions[function] = irFunction
        module.functions.add(irFunction)
        val lowering = FunctionLowering(this, irFunction, function, structuredReturns = true, returnSink = null)
        for ((index, parameter) in function.parameters.withIndex()) {
            val value = parameters[index]
            val opaque = parameter.type is TextureType || parameter.type is SamplerType ||
                (parameter.type is ArrayType && (parameter.type.element is TextureType))
            when {
                parameter.isReference -> lowering.bind(parameter, Binding(value, false))
                opaque -> lowering.bind(parameter, Binding(value, true))
                else -> {
                    val local = lowering.builder.variable(value.type, parameter.name)
                    lowering.builder.store(local, value)
                    lowering.bind(parameter, Binding(local, false))
                }
            }
        }
        lowering.lowerBody(body)
        inProgress.remove(function)
        return irFunction
    }

    fun globalValue(global: GlobalVariable, location: SourceLocation): Value {
        globalValues[global]?.let { return it }
        val value = computeGlobalValue(global, location)
        globalValues[global] = value
        return value
    }

    private fun computeGlobalValue(global: GlobalVariable, location: SourceLocation): Value {
        global.samplerState?.let { state ->
            val variable = gg.sona.msl.ir.GlobalVariable(global.name, IrSampler, StorageClass.UniformConstant)
            variable.resource = ResourceInfo(ResourceKind.Sampler, -1, true, samplerState = state)
            return addGlobal(variable)
        }
        if (global.isFunctionConstant) return functionConstant(global, location)
        val constant = global.constantValue
        val type = global.type
        if (constant != null && (type is ScalarType || type is VectorType || type is MatrixType)) return types.constant(constant)
        return globalPointer(global, location)
    }

    fun globalPointer(global: GlobalVariable, location: SourceLocation): Value {
        globalPointers[global]?.let { return it }
        if (global.samplerState != null) return globalValue(global, location)
        val constant = global.constantValue
        if (constant == null) {
            diagnostics.error(location, "'${global.name}' is not addressable")
        }
        val variable = gg.sona.msl.ir.GlobalVariable(global.name, types.lower(global.type), StorageClass.Private)
        variable.initializer = constant?.let { types.constant(it) } ?: types.zero(variable.valueType)
        addGlobal(variable)
        globalPointers[global] = variable
        return variable
    }

    private fun functionConstant(global: GlobalVariable, location: SourceLocation): Value {
        val index = global.functionConstantIndex
        val type = types.lower(global.type)
        val provided = options.functionConstants[index]
        if (provided != null) {
            val scalar = type as? IrScalar ?: run {
                diagnostics.error(location, "vector function constants are not supported")
                return types.zero(type)
            }
            return constantFrom(scalar, provided, location)
        }
        if (!options.specializeFunctionConstants) {
            diagnostics.error(location, "function constant '${global.name}' (index $index) must be given a value for this target")
            return types.zero(type)
        }
        val scalar = type as? IrScalar ?: run {
            diagnostics.error(location, "vector function constants cannot be specialized")
            return types.zero(type)
        }
        val existing = module.specConstants.firstOrNull { it.specId == index }
        if (existing != null) return existing
        val specConstant = SpecConstant(scalar, index, Constants.zero(scalar) as ConstantScalar, global.name)
        module.specConstants.add(specConstant)
        return specConstant
    }

    private fun constantFrom(type: IrScalar, value: Any, location: SourceLocation): ConstantScalar = when (value) {
        is Boolean -> Constants.integer(type, if (value) 1 else 0)
        is Float -> Constants.scalar(type, value.toDouble())
        is Double -> Constants.scalar(type, value)
        is Number -> Constants.integer(type, value.toLong())
        else -> {
            diagnostics.error(location, "unsupported function constant value '$value'")
            Constants.zero(type) as ConstantScalar
        }
    }

    fun functionConstantDefined(global: GlobalVariable): Value {
        definedFlags[global]?.let { return it }
        val index = global.functionConstantIndex
        val value: Value = when {
            index in options.functionConstants -> ConstantScalar.bool(true)
            !options.specializeFunctionConstants -> ConstantScalar.bool(false)
            else -> SpecConstant(IrBool, options.functionConstantDefinedSpecIdBase + index, ConstantScalar.bool(false), "${global.name}.defined")
                .also { module.specConstants.add(it) }
        }
        definedFlags[global] = value
        return value
    }

    fun threadgroupVariable(local: LocalVariable): Value = threadgroupLocals.getOrPut(local) {
        val variable = gg.sona.msl.ir.GlobalVariable(uniqueName(local.name), types.lower(local.type), StorageClass.Workgroup)
        addGlobal(variable)
    }

    fun threadgroupArray(name: String, pointee: gg.sona.msl.types.Type, index: Int): gg.sona.msl.ir.GlobalVariable {
        val element = types.lower(pointee)
        val stride = TypeLayout.stride(pointee).coerceAtLeast(1)
        val bytes = options.threadgroupMemoryLengths[index] ?: run {
            diagnostics.warning(
                SourceLocation.NONE,
                "threadgroup memory length for [[threadgroup($index)]] is not specified; defaulting to ${options.defaultThreadgroupMemoryLength} bytes",
            )
            options.defaultThreadgroupMemoryLength
        }
        val count = (bytes / stride).coerceAtLeast(1)
        val variable = gg.sona.msl.ir.GlobalVariable(name, IrArray(element, count, stride), StorageClass.Workgroup)
        if (options.specializeFunctionConstants) {
            val specConstant = SpecConstant(IrInt.U32, options.threadgroupMemorySpecIdBase + index, ConstantScalar.u32(count), "$name.length")
            module.specConstants.add(specConstant)
            variable.lengthSpecialization = specConstant
        }
        return addGlobal(variable)
    }

    fun pointerType(type: PointerType): IrType = types.lower(type)

    fun isFloat(type: IrType): Boolean = type.scalar is IrFloat
}
