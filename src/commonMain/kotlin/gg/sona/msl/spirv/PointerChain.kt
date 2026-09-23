package gg.sona.msl.spirv

import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.StorageClass

class PointerChain(
    val base: Int,
    val indices: List<Int>,
    val pointee: IrType,
    val storage: StorageClass,
    val arrayStep: Boolean,
)
