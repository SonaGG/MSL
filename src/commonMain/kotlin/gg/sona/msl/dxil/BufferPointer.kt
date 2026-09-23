package gg.sona.msl.dxil

import gg.sona.msl.ir.IrType
import gg.sona.msl.llvm.LlvmValue

class BufferPointer(
    val resource: DxilResource,
    val offset: LlvmValue,
    val constantOffset: Int?,
    override val type: IrType,
    val stride: Int,
) : DxilPointer()
