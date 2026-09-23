package gg.sona.msl.llvm

class LlvmBlock(val function: LlvmFunction) {
    val instructions = ArrayList<LlvmInstruction>()

    val isTerminated: Boolean
        get() = instructions.lastOrNull()?.opcode?.isTerminator == true

    fun add(instruction: LlvmInstruction): LlvmInstruction {
        instructions.add(instruction)
        return instruction
    }
}
