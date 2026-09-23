package gg.sona.msl.llvm

data class LlvmAggregate(override val type: LlvmType, val elements: List<LlvmConstant>) : LlvmConstant() {
    override fun toString(): String = "$type [${elements.joinToString()}]"
}
