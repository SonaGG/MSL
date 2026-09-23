package gg.sona.msl.llvm

data class LlvmConstantInt(override val type: LlvmIntType, val value: Long) : LlvmConstant() {
    override fun toString(): String = "$type $value"
}
