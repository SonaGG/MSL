package gg.sona.msl.reflect

import gg.sona.msl.ir.WorkgroupSize
import gg.sona.msl.lang.ShaderStage

data class ShaderReflection(
    val entryPoint: String,
    val stage: ShaderStage,
    val resources: List<ReflectedResource>,
    val inputs: List<ReflectedInterface>,
    val outputs: List<ReflectedInterface>,
    val specConstants: List<ReflectedSpecConstant>,
    val workgroupSize: WorkgroupSize?,
)
