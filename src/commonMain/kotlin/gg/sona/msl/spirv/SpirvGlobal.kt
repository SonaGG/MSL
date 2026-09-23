package gg.sona.msl.spirv

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.StorageClass

class SpirvGlobal(
    val id: Int,
    val variable: GlobalVariable,
    val prefix: List<Int>,
    val storage: StorageClass,
)
