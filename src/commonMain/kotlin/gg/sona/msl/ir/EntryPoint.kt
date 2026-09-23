package gg.sona.msl.ir

import gg.sona.msl.lang.ShaderStage

class EntryPoint(
    val name: String,
    val stage: ShaderStage,
    val function: IrFunction,
) {
    val interfaceVariables = ArrayList<GlobalVariable>()
    var workgroupSize: WorkgroupSize = WorkgroupSize(1, 1, 1)
    var earlyFragmentTests = false
    var depthMode: DepthMode? = null
    var usesDiscard = false
}
