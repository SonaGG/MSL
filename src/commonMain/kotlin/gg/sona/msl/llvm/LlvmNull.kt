package gg.sona.msl.llvm

data class LlvmNull(override val type: LlvmType) : LlvmConstant() {
    override fun toString(): String = "$type zeroinitializer"
}
