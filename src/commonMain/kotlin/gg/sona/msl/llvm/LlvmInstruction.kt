package gg.sona.msl.llvm

class LlvmInstruction(
    val opcode: LlvmOpcode,
    override val type: LlvmType,
    val operands: MutableList<LlvmValue> = ArrayList(),
    val targets: MutableList<LlvmBlock> = ArrayList(),
    val immediates: IntArray = IntArray(0),
) : LlvmValue() {
    var callee: LlvmFunction? = null
    var auxiliaryType: LlvmType? = null
    var caseValues: List<LlvmConstantInt> = emptyList()

    val producesValue: Boolean
        get() = type != LlvmVoidType
}
