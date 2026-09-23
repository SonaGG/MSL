package gg.sona.msl.sema

import gg.sona.msl.types.EnumType
import gg.sona.msl.types.ScalarType

object BuiltinEnums {
    private val ADDRESS_MODES = arrayOf("clamp_to_edge", "clamp_to_zero", "clamp_to_border", "repeat", "mirrored_repeat")

    val access = scoped("access", "sample", "read", "write", "read_write")
    val component = scoped("component", "x", "y", "z", "w")
    val memFlags = EnumType("mem_flags", ScalarType.UInt, isScoped = true, isBuiltin = true).apply {
        values["mem_none"] = 0
        values["mem_device"] = 1
        values["mem_threadgroup"] = 2
        values["mem_texture"] = 4
        values["mem_threadgroup_imageblock"] = 8
        values["mem_object_data"] = 16
    }
    val memoryOrder = EnumType("memory_order", ScalarType.UInt, isScoped = false, isBuiltin = true).apply {
        values["memory_order_relaxed"] = 0
        values["memory_order_acquire"] = 2
        values["memory_order_release"] = 3
        values["memory_order_acq_rel"] = 4
        values["memory_order_seq_cst"] = 5
    }
    val threadScope = EnumType("thread_scope", ScalarType.UInt, isScoped = false, isBuiltin = true).apply {
        values["thread_scope_thread"] = 0
        values["thread_scope_simdgroup"] = 1
        values["thread_scope_threadgroup"] = 2
        values["thread_scope_device"] = 3
    }
    val coord = scoped("coord", "normalized", "pixel")
    val address = scoped("address", *ADDRESS_MODES)
    val sAddress = scoped("s_address", *ADDRESS_MODES)
    val tAddress = scoped("t_address", *ADDRESS_MODES)
    val rAddress = scoped("r_address", *ADDRESS_MODES)
    val filter = scoped("filter", "nearest", "linear")
    val magFilter = scoped("mag_filter", "nearest", "linear")
    val minFilter = scoped("min_filter", "nearest", "linear")
    val mipFilter = scoped("mip_filter", "none", "nearest", "linear")
    val compareFunc = scoped(
        "compare_func",
        "none", "less", "less_equal", "greater", "greater_equal", "equal", "not_equal", "always", "never",
    )
    val borderColor = scoped("border_color", "transparent_black", "opaque_black", "opaque_white")

    val all: List<EnumType> = listOf(
        access, component, memFlags, memoryOrder, threadScope, coord, address, sAddress, tAddress, rAddress,
        filter, magFilter, minFilter, mipFilter, compareFunc, borderColor,
    )

    fun install(scope: Scope) {
        for (enum in all) {
            scope.symbols[enum.name] = TypeSymbol(enum)
            if (!enum.isScoped) {
                for ((name, value) in enum.values) scope.symbols[name] = EnumeratorSymbol(enum, value)
            }
        }
    }

    private fun scoped(name: String, vararg values: String): EnumType =
        EnumType(name, ScalarType.UInt, isScoped = true, isBuiltin = true).apply {
            values.forEachIndexed { index, value -> this.values[value] = index.toLong() }
        }
}
