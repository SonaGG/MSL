package gg.sona.msl.llvm

data class LlvmFunctionType(val returnType: LlvmType, val parameters: List<LlvmType>) : LlvmType() {
    override fun toString(): String = "$returnType (${parameters.joinToString()})"
}
