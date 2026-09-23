package gg.sona.msl.ir

class IrBuilder(val function: IrFunction) {
    lateinit var block: Block
        private set

    private var blockCounter = 0
    private var insertIndex = -1

    val isTerminated: Boolean
        get() = block.isTerminated

    fun position(target: Block) {
        block = target
        insertIndex = -1
    }

    fun positionBefore(anchor: Instruction) {
        block = anchor.block!!
        insertIndex = block.instructions.indexOf(anchor)
    }

    fun newBlock(name: String): Block = function.newBlock("$name${blockCounter++}")

    fun emit(
        opcode: Opcode,
        type: IrType,
        operands: List<Value> = emptyList(),
        literals: IntArray = Instruction.EMPTY,
        intrinsic: Intrinsic? = null,
    ): Instruction {
        val instruction = Instruction(opcode, type, operands)
        instruction.literals = literals
        instruction.intrinsic = intrinsic
        if (insertIndex >= 0) {
            instruction.block = block
            block.instructions.add(insertIndex++, instruction)
            return instruction
        }
        if (block.isTerminated) {
            val dead = newBlock("dead")
            position(dead)
        }
        return block.append(instruction)
    }

    fun variable(type: IrType, name: String? = null, initializer: Value? = null): Instruction {
        val instruction = Instruction(Opcode.Variable, IrPointer(type, StorageClass.Function))
        instruction.name = name
        val entry = function.entry
        var index = 0
        while (index < entry.instructions.size && entry.instructions[index].opcode == Opcode.Variable) index++
        instruction.block = entry
        entry.instructions.add(index, instruction)
        if (initializer != null) store(instruction, initializer)
        return instruction
    }

    fun load(pointer: Value): Instruction {
        val pointee = (pointer.type as IrPointer).pointee
        return emit(Opcode.Load, pointee, listOf(pointer))
    }

    fun store(pointer: Value, value: Value): Instruction = emit(Opcode.Store, IrVoid, listOf(pointer, value))

    fun accessChain(base: Value, indices: List<Value>): Value {
        if (indices.isEmpty()) return base
        val pointer = base.type as IrPointer
        var type = pointer.pointee
        for (index in indices) type = elementType(type, index)
        if (base is Instruction && base.opcode == Opcode.AccessChain) {
            return emit(Opcode.AccessChain, IrPointer(type, pointer.storage), base.operands + indices)
        }
        return emit(Opcode.AccessChain, IrPointer(type, pointer.storage), listOf(base) + indices)
    }

    fun ptrOffset(base: Value, offset: Value): Instruction = emit(Opcode.PtrOffset, base.type, listOf(base, offset))

    fun ptrDiff(left: Value, right: Value): Instruction = emit(Opcode.PtrDiff, IrInt.I32, listOf(left, right))

    fun construct(type: IrType, parts: List<Value>): Value {
        if (parts.all { it is IrConstant } && parts.isNotEmpty()) {
            val flattened = flattenConstants(type, parts)
            if (flattened != null) return flattened
        }
        return emit(Opcode.CompositeConstruct, type, parts)
    }

    private fun flattenConstants(type: IrType, parts: List<Value>): IrConstant? {
        if (type is IrVector) {
            val scalars = ArrayList<IrConstant>()
            for (part in parts) {
                when (part) {
                    is ConstantScalar -> scalars.add(part)
                    is ConstantComposite -> scalars.addAll(part.elements)
                    is ConstantNull -> repeat(part.type.componentCount) { scalars.add(Constants.zero(type.element)) }
                    else -> return null
                }
            }
            if (scalars.size != type.count) return null
            return ConstantComposite(type, scalars)
        }
        return ConstantComposite(type, parts.map { it as IrConstant })
    }

    fun extract(composite: Value, vararg indices: Int): Value {
        var type = composite.type
        for (index in indices) type = memberType(type, index)
        if (composite is ConstantComposite && indices.size == 1) return composite.elements[indices[0]]
        if (composite is ConstantNull) return Constants.zero(type)
        return emit(Opcode.CompositeExtract, type, listOf(composite), indices)
    }

    fun insert(composite: Value, value: Value, vararg indices: Int): Instruction =
        emit(Opcode.CompositeInsert, composite.type, listOf(value, composite), indices)

    fun shuffle(first: Value, second: Value, lanes: IntArray): Value {
        val element = first.type.scalar
        if (lanes.size == 1) {
            val count = first.type.componentCount
            return if (lanes[0] < count) extract(first, lanes[0]) else extract(second, lanes[0] - count)
        }
        return emit(Opcode.VectorShuffle, IrVector.of(element, lanes.size), listOf(first, second), lanes)
    }

    fun splat(type: IrType, scalar: Value): Value {
        if (type !is IrVector) return scalar
        return construct(type, List(type.count) { scalar })
    }

    fun select(condition: Value, whenTrue: Value, whenFalse: Value): Instruction =
        emit(Opcode.Select, whenTrue.type, listOf(condition, whenTrue, whenFalse))

    fun binary(opcode: Opcode, type: IrType, left: Value, right: Value): Instruction = emit(opcode, type, listOf(left, right))

    fun unary(opcode: Opcode, type: IrType, operand: Value): Instruction = emit(opcode, type, listOf(operand))

    fun intrinsic(intrinsic: Intrinsic, type: IrType, operands: List<Value>, literals: IntArray = Instruction.EMPTY): Instruction =
        emit(Opcode.Intrinsic, type, operands, literals, intrinsic)

    fun call(callee: IrFunction, arguments: List<Value>): Instruction {
        val instruction = emit(Opcode.Call, callee.returnType, arguments)
        instruction.callee = callee
        return instruction
    }

    fun phi(type: IrType, incoming: List<Pair<Value, Block>>): Instruction {
        val instruction = Instruction(Opcode.Phi, type, incoming.map { it.first })
        instruction.targets.addAll(incoming.map { it.second })
        instruction.block = block
        block.instructions.add(block.phis.size, instruction)
        return instruction
    }

    fun branch(target: Block) {
        if (block.isTerminated) return
        val instruction = Instruction(Opcode.Branch, IrVoid)
        instruction.targets.add(target)
        block.append(instruction)
    }

    fun condBranch(condition: Value, whenTrue: Block, whenFalse: Block) {
        if (block.isTerminated) return
        val instruction = Instruction(Opcode.CondBranch, IrVoid, listOf(condition))
        instruction.targets.add(whenTrue)
        instruction.targets.add(whenFalse)
        block.append(instruction)
    }

    fun switch(selector: Value, default: Block, cases: List<Pair<Int, Block>>) {
        if (block.isTerminated) return
        val instruction = Instruction(Opcode.Switch, IrVoid, listOf(selector))
        instruction.targets.add(default)
        instruction.targets.addAll(cases.map { it.second })
        instruction.literals = cases.map { it.first }.toIntArray()
        block.append(instruction)
    }

    fun ret(value: Value? = null) {
        if (block.isTerminated) return
        block.append(Instruction(Opcode.Return, IrVoid, listOfNotNull(value)))
    }

    fun unreachable() {
        if (block.isTerminated) return
        block.append(Instruction(Opcode.Unreachable, IrVoid))
    }

    companion object {
        fun elementType(type: IrType, index: Value): IrType = when (type) {
            is IrStruct -> {
                val constant = index as? ConstantScalar ?: error("struct index must be constant")
                type.members[constant.bits.toInt()].type
            }

            else -> memberType(type, 0)
        }

        fun memberType(type: IrType, index: Int): IrType = when (type) {
            is IrVector -> type.element
            is IrMatrix -> type.column
            is IrArray -> type.element
            is IrStruct -> type.members[index].type
            else -> error("type $type is not a composite")
        }
    }
}
