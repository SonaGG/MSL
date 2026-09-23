package gg.sona.msl.llvm

class LlvmBuilder(val module: LlvmModule) {
    lateinit var block: LlvmBlock

    fun position(target: LlvmBlock) {
        block = target
    }

    private fun add(instruction: LlvmInstruction): LlvmInstruction = block.add(instruction)

    fun binary(code: Int, left: LlvmValue, right: LlvmValue, flags: Int = 0): LlvmValue =
        add(LlvmInstruction(LlvmOpcode.BinOp, left.type, mutableListOf(left, right), immediates = intArrayOf(code, flags)))

    fun cast(code: Int, value: LlvmValue, type: LlvmType): LlvmValue {
        if (value.type == type && code == CAST_BITCAST) return value
        return add(LlvmInstruction(LlvmOpcode.Cast, type, mutableListOf(value), immediates = intArrayOf(code)))
    }

    fun compare(predicate: Int, left: LlvmValue, right: LlvmValue): LlvmValue =
        add(LlvmInstruction(LlvmOpcode.Cmp, LlvmIntType.I1, mutableListOf(left, right), immediates = intArrayOf(predicate)))

    fun select(condition: LlvmValue, whenTrue: LlvmValue, whenFalse: LlvmValue): LlvmValue {
        if (whenTrue == whenFalse) return whenTrue
        return add(LlvmInstruction(LlvmOpcode.Select, whenTrue.type, mutableListOf(condition, whenTrue, whenFalse)))
    }

    fun phi(type: LlvmType): LlvmInstruction {
        val instruction = LlvmInstruction(LlvmOpcode.Phi, type)
        val index = block.instructions.indexOfFirst { it.opcode != LlvmOpcode.Phi }.let { if (it < 0) block.instructions.size else it }
        block.instructions.add(index, instruction)
        return instruction
    }

    fun call(function: LlvmFunction, arguments: List<LlvmValue>): LlvmValue {
        val instruction = LlvmInstruction(LlvmOpcode.Call, function.functionType.returnType, arguments.toMutableList())
        instruction.callee = function
        return add(instruction)
    }

    fun extractValue(aggregate: LlvmValue, index: Int): LlvmValue {
        val type = when (val aggregateType = aggregate.type) {
            is LlvmStructType -> aggregateType.elements[index]
            is LlvmArrayType -> aggregateType.element
            else -> error("extractvalue from $aggregateType")
        }
        return add(LlvmInstruction(LlvmOpcode.ExtractValue, type, mutableListOf(aggregate), immediates = intArrayOf(index)))
    }

    fun alloca(type: LlvmType, alignment: Int, entry: LlvmBlock): LlvmValue {
        val instruction = LlvmInstruction(
            LlvmOpcode.Alloca,
            LlvmPointerType(type),
            mutableListOf(LlvmConstantInt(LlvmIntType.I32, 1)),
            immediates = intArrayOf(alignment),
        )
        instruction.auxiliaryType = type
        val index = entry.instructions.indexOfFirst { it.opcode != LlvmOpcode.Alloca }.let { if (it < 0) entry.instructions.size else it }
        entry.instructions.add(index, instruction)
        return instruction
    }

    fun load(pointer: LlvmValue, alignment: Int): LlvmValue {
        val type = (pointer.type as LlvmPointerType).pointee
        return add(LlvmInstruction(LlvmOpcode.Load, type, mutableListOf(pointer), immediates = intArrayOf(alignment)))
    }

    fun store(pointer: LlvmValue, value: LlvmValue, alignment: Int) {
        add(LlvmInstruction(LlvmOpcode.Store, LlvmVoidType, mutableListOf(pointer, value), immediates = intArrayOf(alignment)))
    }

    fun elementPointer(pointer: LlvmValue, indices: List<LlvmValue>): LlvmValue {
        val pointerType = pointer.type as LlvmPointerType
        var type = pointerType.pointee
        for (index in indices.drop(1)) {
            type = when (type) {
                is LlvmArrayType -> type.element
                is LlvmStructType -> type.elements[((index as LlvmConstantInt).value).toInt()]
                else -> error("gep into $type")
            }
        }
        val instruction = LlvmInstruction(
            LlvmOpcode.GetElementPtr,
            LlvmPointerType(type, pointerType.addressSpace),
            (listOf(pointer) + indices).toMutableList(),
            immediates = intArrayOf(1),
        )
        instruction.auxiliaryType = pointerType.pointee
        return add(instruction)
    }

    fun atomicRmw(operation: Int, pointer: LlvmValue, value: LlvmValue): LlvmValue =
        add(
            LlvmInstruction(
                LlvmOpcode.AtomicRmw,
                value.type,
                mutableListOf(pointer, value),
                immediates = intArrayOf(operation, ORDERING_SEQ_CST, SYNC_CROSS_THREAD),
            ),
        )

    fun compareExchange(pointer: LlvmValue, comparand: LlvmValue, value: LlvmValue): LlvmValue {
        val type = LlvmStructType(null, listOf(value.type, LlvmIntType.I1))
        return add(
            LlvmInstruction(
                LlvmOpcode.CmpXchg,
                type,
                mutableListOf(pointer, comparand, value),
                immediates = intArrayOf(ORDERING_SEQ_CST, SYNC_CROSS_THREAD, ORDERING_SEQ_CST, 0),
            ),
        )
    }

    fun branch(target: LlvmBlock) {
        if (block.isTerminated) return
        add(LlvmInstruction(LlvmOpcode.Br, LlvmVoidType, targets = mutableListOf(target)))
    }

    fun conditionalBranch(condition: LlvmValue, whenTrue: LlvmBlock, whenFalse: LlvmBlock) {
        if (block.isTerminated) return
        add(LlvmInstruction(LlvmOpcode.Br, LlvmVoidType, mutableListOf(condition), mutableListOf(whenTrue, whenFalse)))
    }

    fun switch(selector: LlvmValue, default: LlvmBlock, cases: List<Pair<LlvmConstantInt, LlvmBlock>>) {
        val instruction = LlvmInstruction(
            LlvmOpcode.Switch,
            LlvmVoidType,
            mutableListOf(selector),
            (listOf(default) + cases.map { it.second }).toMutableList(),
        )
        instruction.caseValues = cases.map { it.first }
        add(instruction)
    }

    fun ret() {
        if (block.isTerminated) return
        add(LlvmInstruction(LlvmOpcode.Ret, LlvmVoidType))
    }

    fun unreachable() {
        if (block.isTerminated) return
        add(LlvmInstruction(LlvmOpcode.Unreachable, LlvmVoidType))
    }

    companion object {
        const val BINOP_ADD = 0
        const val BINOP_SUB = 1
        const val BINOP_MUL = 2
        const val BINOP_UDIV = 3
        const val BINOP_SDIV = 4
        const val BINOP_UREM = 5
        const val BINOP_SREM = 6
        const val BINOP_SHL = 7
        const val BINOP_LSHR = 8
        const val BINOP_ASHR = 9
        const val BINOP_AND = 10
        const val BINOP_OR = 11
        const val BINOP_XOR = 12

        const val CAST_TRUNC = 0
        const val CAST_ZEXT = 1
        const val CAST_SEXT = 2
        const val CAST_FPTOUI = 3
        const val CAST_FPTOSI = 4
        const val CAST_UITOFP = 5
        const val CAST_SITOFP = 6
        const val CAST_FPTRUNC = 7
        const val CAST_FPEXT = 8
        const val CAST_BITCAST = 11

        const val FCMP_OEQ = 1
        const val FCMP_OGT = 2
        const val FCMP_OGE = 3
        const val FCMP_OLT = 4
        const val FCMP_OLE = 5
        const val FCMP_UNE = 14
        const val ICMP_EQ = 32
        const val ICMP_NE = 33
        const val ICMP_UGT = 34
        const val ICMP_UGE = 35
        const val ICMP_ULT = 36
        const val ICMP_ULE = 37
        const val ICMP_SGT = 38
        const val ICMP_SGE = 39
        const val ICMP_SLT = 40
        const val ICMP_SLE = 41

        const val RMW_XCHG = 0
        const val RMW_ADD = 1
        const val RMW_SUB = 2
        const val RMW_AND = 3
        const val RMW_OR = 5
        const val RMW_XOR = 6
        const val RMW_MAX = 7
        const val RMW_MIN = 8
        const val RMW_UMAX = 9
        const val RMW_UMIN = 10

        const val ORDERING_SEQ_CST = 6
        const val SYNC_CROSS_THREAD = 1
    }
}
