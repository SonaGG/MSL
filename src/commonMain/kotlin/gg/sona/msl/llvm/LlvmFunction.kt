package gg.sona.msl.llvm

class LlvmFunction(
    val name: String,
    val functionType: LlvmFunctionType,
    val attributes: Set<LlvmAttribute> = emptySet(),
) : LlvmValue() {
    override val type: LlvmType = LlvmPointerType(functionType)
    val blocks = ArrayList<LlvmBlock>()
    val arguments: List<LlvmArgument> = functionType.parameters.mapIndexed { index, type -> LlvmArgument(type, index) }

    val isDeclaration: Boolean
        get() = blocks.isEmpty()

    fun block(): LlvmBlock = LlvmBlock(this).also { blocks.add(it) }

    override fun toString(): String = "@$name"
}
