package gg.sona.msl.dxil

import gg.sona.msl.ir.IrType
import gg.sona.msl.llvm.LlvmValue

class ResourcePointer(
    val resource: DxilResource,
    val index: LlvmValue?,
    override val type: IrType,
) : DxilPointer()
