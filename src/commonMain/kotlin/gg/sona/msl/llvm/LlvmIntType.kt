package gg.sona.msl.llvm

data class LlvmIntType(val bits: Int) : LlvmType() {
    override fun toString(): String = "i$bits"

    companion object {
        val I1 = LlvmIntType(1)
        val I8 = LlvmIntType(8)
        val I16 = LlvmIntType(16)
        val I32 = LlvmIntType(32)
        val I64 = LlvmIntType(64)
    }
}
