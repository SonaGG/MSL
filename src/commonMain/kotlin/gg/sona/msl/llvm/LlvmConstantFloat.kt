package gg.sona.msl.llvm

data class LlvmConstantFloat(override val type: LlvmFloatType, val bits: Long) : LlvmConstant() {
    override fun toString(): String = "$type bits($bits)"
}
