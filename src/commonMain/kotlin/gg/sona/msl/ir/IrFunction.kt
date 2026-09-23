package gg.sona.msl.ir

class IrFunction(
    val name: String,
    val returnType: IrType,
    val parameters: List<Parameter>,
) {
    val blocks = ArrayList<Block>()

    val entry: Block
        get() = blocks.first()

    fun newBlock(name: String): Block {
        val block = Block(name)
        block.function = this
        blocks.add(block)
        return block
    }

    fun adopt(block: Block) {
        block.function = this
        blocks.add(block)
    }

    fun instructions(): Sequence<Instruction> = blocks.asSequence().flatMap { it.instructions }

    override fun toString(): String = "@$name"
}
