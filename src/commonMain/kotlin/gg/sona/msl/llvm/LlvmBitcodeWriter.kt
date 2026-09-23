package gg.sona.msl.llvm

import gg.sona.msl.bitcode.BitcodeConstants
import gg.sona.msl.bitcode.BitstreamWriter

class LlvmBitcodeWriter(private val module: LlvmModule) {
    private val stream = BitstreamWriter()
    private val typeIds = LinkedHashMap<LlvmType, Int>()
    private val types = ArrayList<LlvmType>()
    private val valueIds = HashMap<LlvmValue, Int>()
    private val constants = ArrayList<LlvmConstant>()
    private val attributeLists = LinkedHashMap<Set<LlvmAttribute>, Int>()
    private val metadataIds = LinkedHashMap<Any, Int>()
    private val metadataList = ArrayList<LlvmMetadata>()
    private var moduleValueCount = 0

    fun write(): ByteArray {
        collect()
        stream.emit(0x42L, 8)
        stream.emit(0x43L, 8)
        stream.emit(0x0L, 4)
        stream.emit(0xCL, 4)
        stream.emit(0xEL, 4)
        stream.emit(0xDL, 4)
        stream.enterBlock(BitcodeConstants.MODULE_BLOCK, 3)
        stream.record(BitcodeConstants.MODULE_VERSION, 1L)
        writeAttributes()
        writeTypes()
        stream.record(BitcodeConstants.MODULE_TRIPLE, chars(module.triple))
        stream.record(BitcodeConstants.MODULE_DATALAYOUT, chars(module.dataLayout))
        writeGlobals()
        writeConstants()
        writeMetadata()
        writeSymbolTable()
        for (function in module.functions) if (!function.isDeclaration) writeFunction(function)
        stream.exitBlock()
        return stream.toByteArray()
    }

    private fun chars(text: String): LongArray = LongArray(text.length) { text[it].code.toLong() }

    private fun typeId(type: LlvmType): Long = typeIds[type]?.toLong() ?: error("unregistered type $type")

    private fun addType(type: LlvmType) {
        if (type in typeIds) return
        when (type) {
            is LlvmPointerType -> addType(type.pointee)
            is LlvmArrayType -> addType(type.element)
            is LlvmVectorType -> addType(type.element)
            is LlvmStructType -> type.elements.forEach { addType(it) }
            is LlvmFunctionType -> {
                addType(type.returnType)
                type.parameters.forEach { addType(it) }
            }

            else -> Unit
        }
        if (type in typeIds) return
        typeIds[type] = types.size
        types.add(type)
    }

    private fun addConstant(constant: LlvmConstant) {
        if (constant in valueIds) return
        addType(constant.type)
        if (constant is LlvmAggregate) constant.elements.forEach { addConstant(it) }
        if (constant in valueIds) return
        valueIds[constant] = -1
        constants.add(constant)
    }

    private fun addOperand(value: LlvmValue) {
        addType(value.type)
        if (value is LlvmConstant) addConstant(value)
    }

    private fun addMetadata(metadata: LlvmMetadata?) {
        if (metadata == null) return
        val key: Any = if (metadata is MdNode) IdentityKey(metadata) else metadata
        if (key in metadataIds) return
        when (metadata) {
            is MdNode -> metadata.elements.forEach { addMetadata(it) }
            is MdValue -> addOperand(metadata.value)
            is MdString -> Unit
        }
        if (key in metadataIds) return
        metadataIds[key] = metadataList.size
        metadataList.add(metadata)
    }

    private fun metadataId(metadata: LlvmMetadata): Int {
        val key: Any = if (metadata is MdNode) IdentityKey(metadata) else metadata
        return metadataIds.getValue(key)
    }

    private fun collect() {
        addType(LlvmVoidType)
        for (global in module.globals) {
            addType(global.valueType)
            addType(global.type)
            global.initializer?.let { addConstant(it) }
        }
        for (function in module.functions) {
            addType(function.functionType)
            addType(function.type)
            if (function.attributes.isNotEmpty()) attributeLists.getOrPut(function.attributes) { attributeLists.size + 1 }
            for (block in function.blocks) {
                for (instruction in block.instructions) {
                    addType(instruction.type)
                    instruction.auxiliaryType?.let { addType(it) }
                    instruction.operands.forEach { addOperand(it) }
                    instruction.caseValues.forEach { addConstant(it) }
                    instruction.callee?.let { addType(it.functionType) }
                }
            }
        }
        for ((_, nodes) in module.namedMetadata) nodes.forEach { addMetadata(it) }
        var next = 0
        for (global in module.globals) valueIds[global] = next++
        for (function in module.functions) valueIds[function] = next++
        constants.sortWith(compareBy { typeIds.getValue(it.type) })
        val ordered = ArrayList<LlvmConstant>()
        val placed = HashSet<LlvmConstant>()
        fun place(constant: LlvmConstant) {
            if (!placed.add(constant)) return
            if (constant is LlvmAggregate) constant.elements.forEach { place(it) }
            ordered.add(constant)
        }
        constants.forEach { place(it) }
        constants.clear()
        constants.addAll(ordered)
        for (constant in constants) valueIds[constant] = next++
        moduleValueCount = next
    }

    private fun writeAttributes() {
        if (attributeLists.isEmpty()) return
        stream.enterBlock(BitcodeConstants.PARAMATTR_GROUP_BLOCK, 3)
        for ((attributes, group) in attributeLists) {
            val operands = ArrayList<Long>()
            operands.add(group.toLong())
            operands.add(0xFFFFFFFFL)
            for (attribute in attributes.sortedBy { it.code }) {
                operands.add(0)
                operands.add(attribute.code.toLong())
            }
            stream.record(BitcodeConstants.PARAMATTR_GROUP_ENTRY, operands)
        }
        stream.exitBlock()
        stream.enterBlock(BitcodeConstants.PARAMATTR_BLOCK, 3)
        for ((_, group) in attributeLists) stream.record(BitcodeConstants.PARAMATTR_ENTRY, group.toLong())
        stream.exitBlock()
    }

    private fun writeTypes() {
        stream.enterBlock(BitcodeConstants.TYPE_BLOCK, 4)
        stream.record(BitcodeConstants.TYPE_NUMENTRY, types.size.toLong())
        for (type in types) {
            when (type) {
                LlvmVoidType -> stream.record(BitcodeConstants.TYPE_VOID)
                LlvmLabelType -> stream.record(BitcodeConstants.TYPE_LABEL)
                LlvmMetadataType -> stream.record(BitcodeConstants.TYPE_METADATA)
                is LlvmIntType -> stream.record(BitcodeConstants.TYPE_INTEGER, type.bits.toLong())
                is LlvmFloatType -> stream.record(
                    when (type.bits) {
                        16 -> BitcodeConstants.TYPE_HALF
                        32 -> BitcodeConstants.TYPE_FLOAT
                        else -> BitcodeConstants.TYPE_DOUBLE
                    },
                )

                is LlvmPointerType -> stream.record(BitcodeConstants.TYPE_POINTER, typeId(type.pointee), type.addressSpace.toLong())
                is LlvmArrayType -> stream.record(BitcodeConstants.TYPE_ARRAY, type.count.toLong(), typeId(type.element))
                is LlvmVectorType -> stream.record(BitcodeConstants.TYPE_VECTOR, type.count.toLong(), typeId(type.element))
                is LlvmStructType -> {
                    val elements = type.elements.map { typeId(it) }
                    val packed = if (type.packed) 1L else 0L
                    if (type.name != null) {
                        stream.record(BitcodeConstants.TYPE_STRUCT_NAME, chars(type.name))
                        stream.record(BitcodeConstants.TYPE_STRUCT_NAMED, listOf(packed) + elements)
                    } else {
                        stream.record(BitcodeConstants.TYPE_STRUCT_ANON, listOf(packed) + elements)
                    }
                }

                is LlvmFunctionType -> stream.record(
                    BitcodeConstants.TYPE_FUNCTION,
                    listOf(0L, typeId(type.returnType)) + type.parameters.map { typeId(it) },
                )
            }
        }
        stream.exitBlock()
    }

    private fun log2Alignment(alignment: Int): Long {
        if (alignment <= 0) return 0
        var value = alignment
        var log = 0
        while (value > 1) {
            value = value shr 1
            log++
        }
        return (log + 1).toLong()
    }

    private fun writeGlobals() {
        for (global in module.globals) {
            val initializer = global.initializer?.let { valueIds.getValue(it) + 1L } ?: 0L
            val flags = (global.addressSpace.toLong() shl 2) or 2L or (if (global.isConstant) 1L else 0L)
            val unnamed = if (global.linkage == LlvmLinkage.Internal) 1L else 0L
            stream.record(
                BitcodeConstants.MODULE_GLOBALVAR,
                typeId(global.valueType), flags, initializer, global.linkage.code.toLong(), log2Alignment(global.alignment),
                0L, 0L, 0L, unnamed, 0L, 0L, 0L,
            )
        }
        for (function in module.functions) {
            val attributes = attributeLists[function.attributes]?.toLong() ?: 0L
            stream.record(
                BitcodeConstants.MODULE_FUNCTION,
                typeId(function.functionType), 0L, if (function.isDeclaration) 1L else 0L, 0L, attributes,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            )
        }
    }

    private fun signed(value: Long): Long = if (value >= 0) value shl 1 else ((-value) shl 1) or 1L

    private fun signExtend(value: Long, bits: Int): Long = if (bits >= 64) value else (value shl (64 - bits)) shr (64 - bits)

    private fun writeConstants() {
        if (constants.isEmpty()) return
        stream.enterBlock(BitcodeConstants.CONSTANTS_BLOCK, 4)
        var currentType: LlvmType? = null
        for (constant in constants) {
            if (constant.type != currentType) {
                stream.record(BitcodeConstants.CST_SETTYPE, typeId(constant.type))
                currentType = constant.type
            }
            when (constant) {
                is LlvmConstantInt -> stream.record(BitcodeConstants.CST_INTEGER, signed(signExtend(constant.value, constant.type.bits)))
                is LlvmConstantFloat -> stream.record(BitcodeConstants.CST_FLOAT, constant.bits)
                is LlvmUndef -> stream.record(BitcodeConstants.CST_UNDEF)
                is LlvmNull -> stream.record(BitcodeConstants.CST_NULL)
                is LlvmAggregate -> stream.record(BitcodeConstants.CST_AGGREGATE, constant.elements.map { valueIds.getValue(it).toLong() })
            }
        }
        stream.exitBlock()
    }

    private fun writeMetadata() {
        if (metadataList.isEmpty()) return
        stream.enterBlock(BitcodeConstants.METADATA_BLOCK, 3)
        for (metadata in metadataList) {
            when (metadata) {
                is MdString -> stream.record(BitcodeConstants.METADATA_STRING, chars(metadata.value))
                is MdValue -> stream.record(
                    BitcodeConstants.METADATA_VALUE,
                    typeId(metadata.value.type),
                    valueIds.getValue(metadata.value).toLong(),
                )

                is MdNode -> stream.record(
                    BitcodeConstants.METADATA_NODE,
                    metadata.elements.map { element -> if (element == null) 0L else metadataId(element) + 1L },
                )
            }
        }
        for ((name, nodes) in module.namedMetadata) {
            stream.record(BitcodeConstants.METADATA_NAME, chars(name))
            stream.record(BitcodeConstants.METADATA_NAMED_NODE, nodes.map { metadataId(it).toLong() })
        }
        stream.exitBlock()
    }

    private fun writeSymbolTable() {
        stream.enterBlock(BitcodeConstants.VALUE_SYMTAB_BLOCK, 4)
        for (global in module.globals) {
            stream.record(BitcodeConstants.VST_ENTRY, longArrayOf(valueIds.getValue(global).toLong()) + chars(global.name))
        }
        for (function in module.functions) {
            stream.record(BitcodeConstants.VST_ENTRY, longArrayOf(valueIds.getValue(function).toLong()) + chars(function.name))
        }
        stream.exitBlock()
    }

    private fun writeFunction(function: LlvmFunction) {
        val local = HashMap<LlvmValue, Int>()
        var next = moduleValueCount
        for (argument in function.arguments) local[argument] = next++
        for (block in function.blocks) {
            for (instruction in block.instructions) if (instruction.producesValue) local[instruction] = next++
        }
        val blockIds = HashMap<LlvmBlock, Int>()
        function.blocks.forEachIndexed { index, block -> blockIds[block] = index }
        fun id(value: LlvmValue): Int = local[value] ?: valueIds[value] ?: error("unnumbered value $value")
        stream.enterBlock(BitcodeConstants.FUNCTION_BLOCK, 4)
        stream.record(BitcodeConstants.FUNC_DECLAREBLOCKS, function.blocks.size.toLong())
        var instructionId = moduleValueCount + function.arguments.size
        for (block in function.blocks) {
            for (instruction in block.instructions) {
                val operands = ArrayList<Long>()
                fun relative(value: LlvmValue): Long = (instructionId - id(value)).toLong() and 0xFFFFFFFFL
                fun value(value: LlvmValue) {
                    operands.add(relative(value))
                }

                fun valueAndType(value: LlvmValue) {
                    operands.add(relative(value))
                    if (id(value) >= instructionId) operands.add(typeId(value.type))
                }
                val code = when (instruction.opcode) {
                    LlvmOpcode.Ret -> {
                        instruction.operands.firstOrNull()?.let { valueAndType(it) }
                        BitcodeConstants.FUNC_RET
                    }

                    LlvmOpcode.Br -> {
                        operands.add(blockIds.getValue(instruction.targets[0]).toLong())
                        if (instruction.targets.size == 2) {
                            operands.add(blockIds.getValue(instruction.targets[1]).toLong())
                            value(instruction.operands[0])
                        }
                        BitcodeConstants.FUNC_BR
                    }

                    LlvmOpcode.Switch -> {
                        operands.add(typeId(instruction.operands[0].type))
                        value(instruction.operands[0])
                        operands.add(blockIds.getValue(instruction.targets[0]).toLong())
                        instruction.caseValues.forEachIndexed { index, case ->
                            operands.add(valueIds.getValue(case).toLong())
                            operands.add(blockIds.getValue(instruction.targets[index + 1]).toLong())
                        }
                        BitcodeConstants.FUNC_SWITCH
                    }

                    LlvmOpcode.Unreachable -> BitcodeConstants.FUNC_UNREACHABLE
                    LlvmOpcode.BinOp -> {
                        valueAndType(instruction.operands[0])
                        value(instruction.operands[1])
                        operands.add(instruction.immediates[0].toLong())
                        if (instruction.immediates.size > 1 && instruction.immediates[1] != 0) {
                            operands.add(instruction.immediates[1].toLong())
                        }
                        BitcodeConstants.FUNC_BINOP
                    }

                    LlvmOpcode.Cast -> {
                        valueAndType(instruction.operands[0])
                        operands.add(typeId(instruction.type))
                        operands.add(instruction.immediates[0].toLong())
                        BitcodeConstants.FUNC_CAST
                    }

                    LlvmOpcode.Cmp -> {
                        valueAndType(instruction.operands[0])
                        value(instruction.operands[1])
                        operands.add(instruction.immediates[0].toLong())
                        BitcodeConstants.FUNC_CMP2
                    }

                    LlvmOpcode.Select -> {
                        valueAndType(instruction.operands[1])
                        value(instruction.operands[2])
                        valueAndType(instruction.operands[0])
                        BitcodeConstants.FUNC_VSELECT
                    }

                    LlvmOpcode.Phi -> {
                        operands.add(typeId(instruction.type))
                        for (i in instruction.operands.indices) {
                            operands.add(signed((instructionId - id(instruction.operands[i])).toLong()))
                            operands.add(blockIds.getValue(instruction.targets[i]).toLong())
                        }
                        BitcodeConstants.FUNC_PHI
                    }

                    LlvmOpcode.Call -> {
                        val callee = instruction.callee!!
                        operands.add(0)
                        operands.add(1L shl 15)
                        operands.add(typeId(callee.functionType))
                        valueAndType(callee)
                        for (argument in instruction.operands) value(argument)
                        BitcodeConstants.FUNC_CALL
                    }

                    LlvmOpcode.ExtractValue -> {
                        valueAndType(instruction.operands[0])
                        instruction.immediates.forEach { operands.add(it.toLong()) }
                        BitcodeConstants.FUNC_EXTRACTVAL
                    }

                    LlvmOpcode.InsertValue -> {
                        valueAndType(instruction.operands[0])
                        valueAndType(instruction.operands[1])
                        instruction.immediates.forEach { operands.add(it.toLong()) }
                        BitcodeConstants.FUNC_INSERTVAL
                    }

                    LlvmOpcode.Alloca -> {
                        operands.add(typeId(instruction.auxiliaryType!!))
                        operands.add(typeId(instruction.operands[0].type))
                        operands.add(id(instruction.operands[0]).toLong())
                        operands.add(log2Alignment(instruction.immediates[0]) or 64L)
                        BitcodeConstants.FUNC_ALLOCA
                    }

                    LlvmOpcode.Load -> {
                        valueAndType(instruction.operands[0])
                        operands.add(typeId(instruction.type))
                        operands.add(log2Alignment(instruction.immediates[0]))
                        operands.add(0)
                        BitcodeConstants.FUNC_LOAD
                    }

                    LlvmOpcode.Store -> {
                        valueAndType(instruction.operands[0])
                        valueAndType(instruction.operands[1])
                        operands.add(log2Alignment(instruction.immediates[0]))
                        operands.add(0)
                        BitcodeConstants.FUNC_STORE
                    }

                    LlvmOpcode.GetElementPtr -> {
                        operands.add(instruction.immediates[0].toLong())
                        operands.add(typeId(instruction.auxiliaryType!!))
                        instruction.operands.forEach { valueAndType(it) }
                        BitcodeConstants.FUNC_GEP
                    }

                    LlvmOpcode.AtomicRmw -> {
                        valueAndType(instruction.operands[0])
                        value(instruction.operands[1])
                        operands.add(instruction.immediates[0].toLong())
                        operands.add(0)
                        operands.add(instruction.immediates[1].toLong())
                        operands.add(instruction.immediates[2].toLong())
                        BitcodeConstants.FUNC_ATOMICRMW
                    }

                    LlvmOpcode.CmpXchg -> {
                        valueAndType(instruction.operands[0])
                        valueAndType(instruction.operands[1])
                        value(instruction.operands[2])
                        operands.add(0)
                        operands.add(instruction.immediates[0].toLong())
                        operands.add(instruction.immediates[1].toLong())
                        operands.add(instruction.immediates[2].toLong())
                        operands.add(instruction.immediates[3].toLong())
                        BitcodeConstants.FUNC_CMPXCHG
                    }
                }
                stream.record(code, operands)
                if (instruction.producesValue) instructionId++
            }
        }
        stream.exitBlock()
    }
}
