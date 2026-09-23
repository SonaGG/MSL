package gg.sona.msl.dxil

import gg.sona.msl.llvm.LlvmFloatType
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmPointerType
import gg.sona.msl.llvm.LlvmStructType
import gg.sona.msl.llvm.LlvmType

class DxilTypes {
    val handle = LlvmStructType("dx.types.Handle", listOf(LlvmPointerType(LlvmIntType.I8)))
    val dimensions = LlvmStructType("dx.types.Dimensions", List(4) { LlvmIntType.I32 })
    val twoI32 = LlvmStructType("dx.types.twoi32", List(2) { LlvmIntType.I32 })
    val fourI32 = LlvmStructType("dx.types.fouri32", List(4) { LlvmIntType.I32 })
    private val resRet = HashMap<LlvmType, LlvmStructType>()
    private val cbufRet = HashMap<LlvmType, LlvmStructType>()
    private val resources = HashMap<String, LlvmStructType>()

    fun resRet(element: LlvmType): LlvmStructType = resRet.getOrPut(element) {
        LlvmStructType("dx.types.ResRet.${suffix(element)}", List(4) { element } + LlvmIntType.I32)
    }

    fun cbufRet(element: LlvmType): LlvmStructType = cbufRet.getOrPut(element) {
        val count = when {
            element is LlvmFloatType && element.bits == 64 || element is LlvmIntType && element.bits == 64 -> 2
            element is LlvmFloatType && element.bits == 16 || element is LlvmIntType && element.bits == 16 -> 8
            else -> 4
        }
        LlvmStructType("dx.types.CBufRet.${suffix(element)}", List(count) { element })
    }

    fun resource(name: String, element: LlvmType = LlvmIntType.I32): LlvmStructType = resources.getOrPut(name) { LlvmStructType(name, listOf(element)) }

    companion object {
        fun suffix(type: LlvmType): String = when (type) {
            is LlvmFloatType -> "f${type.bits}"
            is LlvmIntType -> "i${type.bits}"
            else -> error("no overload suffix for $type")
        }
    }
}
