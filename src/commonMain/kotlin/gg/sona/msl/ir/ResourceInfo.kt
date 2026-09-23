package gg.sona.msl.ir

import gg.sona.msl.hir.SamplerState

class ResourceInfo(
    val kind: ResourceKind,
    val mslIndex: Int,
    val readOnly: Boolean,
    val arraySize: Int = 1,
    val samplerState: SamplerState? = null,
) {
    var set: Int = 0
    var binding: Int = 0
    var space: Int = 0
    var register: Int = 0

    val isConstexprSampler: Boolean
        get() = samplerState != null
}
