package gg.sona.msl.llvm

class LlvmGlobalVariable(
    val name: String,
    val valueType: LlvmType,
    val addressSpace: Int,
    val isConstant: Boolean,
    val initializer: LlvmConstant?,
    val linkage: LlvmLinkage,
    val alignment: Int,
) : LlvmValue() {
    override val type: LlvmType = LlvmPointerType(valueType, addressSpace)

    override fun toString(): String = "@$name"
}
