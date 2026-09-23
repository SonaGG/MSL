package gg.sona.msl.reflect

import gg.sona.msl.ir.ResourceKind

data class ResourceBindingRequest(
    val name: String,
    val kind: ResourceKind,
    val mslIndex: Int,
    val isConstexprSampler: Boolean,
    val argumentBuffer: Int = -1,
)
