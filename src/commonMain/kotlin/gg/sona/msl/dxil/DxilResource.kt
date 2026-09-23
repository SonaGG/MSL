package gg.sona.msl.dxil

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.llvm.LlvmValue

class DxilResource(
    val variable: GlobalVariable,
    val resourceClass: DxilResourceClass,
    val kind: Int,
    val space: Int,
    val register: Int,
    val count: Int,
    val elementType: Int,
    val sizeInBytes: Int,
) {
    var id = 0
    var handle: LlvmValue? = null
    var comparison = false

    val isRaw: Boolean
        get() = kind == DxilResourceKind.RAW_BUFFER
}
