package gg.sona.msl.dxil

import gg.sona.msl.ir.IrType

class StorageNode(
    val type: IrType,
    val depth: Int,
    val members: List<StorageNode>? = null,
    val element: StorageNode? = null,
    val length: Int = 0,
    val leaf: StorageLeaf? = null,
)
