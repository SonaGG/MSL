package gg.sona.msl.ir

class Block(var name: String) {
    val instructions = ArrayList<Instruction>()
    var construct: ConstructKind = ConstructKind.None
    var merge: Block? = null
    var continueTarget: Block? = null
    var function: IrFunction? = null
    var id: Int = -1

    val terminator: Instruction?
        get() = instructions.lastOrNull()?.takeIf { it.isTerminator }

    val isTerminated: Boolean
        get() = terminator != null

    val successors: List<Block>
        get() = terminator?.targets ?: emptyList()

    val phis: List<Instruction>
        get() = instructions.takeWhile { it.opcode == Opcode.Phi }

    fun append(instruction: Instruction): Instruction {
        check(!isTerminated) { "block $name is already terminated" }
        instruction.block = this
        instructions.add(instruction)
        return instruction
    }

    fun insertBeforeTerminator(instruction: Instruction): Instruction {
        instruction.block = this
        val index = if (isTerminated) instructions.size - 1 else instructions.size
        instructions.add(index, instruction)
        return instruction
    }

    fun insertAfterPhis(instruction: Instruction): Instruction {
        instruction.block = this
        instructions.add(phis.size, instruction)
        return instruction
    }

    fun copyConstructFrom(other: Block) {
        construct = other.construct
        merge = other.merge
        continueTarget = other.continueTarget
    }

    fun clearConstruct() {
        construct = ConstructKind.None
        merge = null
        continueTarget = null
    }

    override fun toString(): String = name
}
