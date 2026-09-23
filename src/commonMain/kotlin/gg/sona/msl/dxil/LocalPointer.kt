package gg.sona.msl.dxil

import gg.sona.msl.ir.IrType
import gg.sona.msl.llvm.LlvmValue

class LocalPointer(
    val node: StorageNode,
    val indices: List<LlvmValue>,
    override val type: IrType,
) : DxilPointer()
