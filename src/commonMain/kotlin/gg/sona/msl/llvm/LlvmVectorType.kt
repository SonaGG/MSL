package gg.sona.msl.llvm

data class LlvmVectorType(val element: LlvmType, val count: Int) : LlvmType() {
    override fun toString(): String = "<$count x $element>"
}
