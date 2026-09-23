package gg.sona.msl.reflect

import gg.sona.msl.hir.SamplerState
import gg.sona.msl.ir.ResourceKind

data class ReflectedResource(
    val name: String,
    val kind: ResourceKind,
    val mslIndex: Int,
    val set: Int,
    val binding: Int,
    val readOnly: Boolean,
    val arraySize: Int,
    val sizeInBytes: Int,
    val samplerState: SamplerState?,
    val argumentBuffer: Int = -1,
    val space: Int = 0,
    val register: Int = 0,
)
