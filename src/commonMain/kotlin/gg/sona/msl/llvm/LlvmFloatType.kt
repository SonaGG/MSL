package gg.sona.msl.llvm

data class LlvmFloatType(val bits: Int) : LlvmType() {
    override fun toString(): String = when (bits) {
        16 -> "half"
        32 -> "float"
        else -> "double"
    }

    companion object {
        val Half = LlvmFloatType(16)
        val Float = LlvmFloatType(32)
        val Double = LlvmFloatType(64)
    }
}
