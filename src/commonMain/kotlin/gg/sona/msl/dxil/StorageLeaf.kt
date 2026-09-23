package gg.sona.msl.dxil

import gg.sona.msl.ir.IrScalar
import gg.sona.msl.llvm.LlvmArrayType
import gg.sona.msl.llvm.LlvmValue

class StorageLeaf(
    val scalar: IrScalar,
    val dimensions: List<Int>,
    val arrayType: LlvmArrayType,
) {
    lateinit var pointer: LlvmValue

    val strides: IntArray = IntArray(dimensions.size) { index ->
        var stride = 1
        for (inner in index + 1 until dimensions.size) stride *= dimensions[inner]
        stride
    }
}
