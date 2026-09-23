package gg.sona.msl.llvm

data class LlvmUndef(override val type: LlvmType) : LlvmConstant() {
    override fun toString(): String = "$type undef"
}
