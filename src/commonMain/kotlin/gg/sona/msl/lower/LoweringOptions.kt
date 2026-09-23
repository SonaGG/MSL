package gg.sona.msl.lower

import gg.sona.msl.ir.WorkgroupSize

class LoweringOptions(
    val functionConstants: Map<Int, Any> = emptyMap(),
    val specializeFunctionConstants: Boolean = true,
    val threadgroupSizes: Map<String, WorkgroupSize> = emptyMap(),
    val specializableThreadgroupSize: Boolean = true,
    val threadgroupMemoryLengths: Map<Int, Int> = emptyMap(),
    val defaultThreadgroupMemoryLength: Int = 4096,
    val entryPoints: Set<String>? = null,
    val functionConstantDefinedSpecIdBase: Int = 0x10000,
    val threadgroupSizeSpecIdBase: Int = 0x20000,
    val threadgroupMemorySpecIdBase: Int = 0x30000,
)
