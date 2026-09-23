package gg.sona.msl.reflect

import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.lang.ShaderStage

object Reflector {
    fun reflect(module: IrModule, entry: EntryPoint): ShaderReflection {
        val globals = entry.interfaceVariables
        val resources = globals.mapNotNull { global ->
            val resource = global.resource ?: return@mapNotNull null
            ReflectedResource(
                global.name,
                resource.kind,
                resource.mslIndex,
                resource.set,
                resource.binding,
                resource.readOnly,
                resource.arraySize,
                size(global.valueType),
                resource.samplerState,
                resource.argumentBuffer,
                resource.space,
                resource.register,
            )
        }
        return ShaderReflection(
            entry.name,
            entry.stage,
            resources,
            interfaces(globals, StorageClass.Input),
            interfaces(globals, StorageClass.Output),
            module.specConstants.map { ReflectedSpecConstant(it.name, it.specId, it.type.toString()) },
            if (entry.stage == ShaderStage.Kernel) entry.workgroupSize else null,
        )
    }

    private fun interfaces(globals: List<GlobalVariable>, storage: StorageClass): List<ReflectedInterface> =
        globals.filter { it.storage == storage && it.interfaceInfo != null }.map {
            val info = it.interfaceInfo!!
            ReflectedInterface(info.name, info.location, info.builtin, it.valueType.toString())
        }

    private fun size(type: IrType): Int = when (type) {
        is IrStruct -> type.size
        is IrArray -> if (type.isRuntime) type.stride else type.stride * type.length
        else -> 0
    }
}
