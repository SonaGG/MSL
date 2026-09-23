package gg.sona.msl.ir

class Instruction(
    var opcode: Opcode,
    override var type: IrType,
    operands: List<Value> = emptyList(),
) : Value() {
    val operands: ArrayList<Value> = ArrayList(operands)
    val targets = ArrayList<Block>()
    var literals: IntArray = EMPTY
    var intrinsic: Intrinsic? = null
    var callee: IrFunction? = null
    var block: Block? = null
    var name: String? = null
    var id: Int = -1

    val isTerminator: Boolean
        get() = opcode.isTerminator

    fun operand(index: Int): Value = operands[index]

    fun replaceOperands(mapping: Map<Value, Value>) {
        for (i in operands.indices) {
            val replacement = mapping[operands[i]] ?: continue
            operands[i] = replacement
        }
    }

    fun replaceTarget(from: Block, to: Block) {
        for (i in targets.indices) if (targets[i] === from) targets[i] = to
    }

    override fun toString(): String = if (id >= 0) "%$id" else "%${name ?: "?"}"

    companion object {
        val EMPTY = IntArray(0)
    }
}
