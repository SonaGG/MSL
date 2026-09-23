package gg.sona.msl.ir

object IrPrinter {
    fun print(module: IrModule): String = buildString {
        for (struct in module.structs) {
            append("struct ").append(struct.name).append(" { ")
            append(struct.members.joinToString("; ")).append(" } size ").append(struct.size).append('\n')
        }
        for (global in module.globals) {
            append("global @").append(global.name).append(": ").append(global.valueType)
            append(" ").append(global.storage.name.lowercase())
            global.resource?.let { append(" resource(").append(it.kind).append(", ").append(it.mslIndex).append(')') }
            global.interfaceInfo?.let { info ->
                append(if (info.isInput) " in" else " out")
                if (info.builtin != null) append(" builtin(").append(info.builtin).append(')') else append(" location(").append(info.location).append(')')
                if (info.interpolation != Interpolation.Perspective) append(' ').append(info.interpolation.name.lowercase())
            }
            global.initializer?.let { append(" = ").append(it) }
            append('\n')
        }
        for (constant in module.specConstants) append("spec ").append(constant.specId).append(' ').append(constant.name).append(" = ").append(constant.default).append('\n')
        for (entry in module.entryPoints) {
            append("entry ").append(entry.stage.keyword).append(' ').append(entry.name).append(" -> @").append(entry.function.name)
            append(" [").append(entry.interfaceVariables.joinToString { "@${it.name}" }).append("]\n")
        }
        for (function in module.functions) append(print(function))
    }

    fun print(function: IrFunction): String = buildString {
        number(function)
        append("fn @").append(function.name).append('(')
        append(function.parameters.joinToString { "${it.type} %${it.name}" }).append("): ").append(function.returnType).append(" {\n")
        for (block in function.blocks) {
            append(block.name).append(':')
            when (block.construct) {
                ConstructKind.Selection -> append(" ; selection merge=").append(block.merge?.name)
                ConstructKind.Loop -> append(" ; loop merge=").append(block.merge?.name).append(" continue=").append(block.continueTarget?.name)
                ConstructKind.None -> Unit
            }
            append('\n')
            for (instruction in block.instructions) append("  ").append(format(instruction)).append('\n')
        }
        append("}\n")
    }

    private fun number(function: IrFunction) {
        var next = 0
        for (block in function.blocks) {
            for (instruction in block.instructions) {
                instruction.id = if (instruction.type == IrVoid) -1 else next++
            }
        }
    }

    fun format(instruction: Instruction): String = buildString {
        if (instruction.type != IrVoid) append(instruction).append(" = ")
        append(instruction.opcode.name)
        instruction.intrinsic?.let { append('.').append(it.name) }
        instruction.callee?.let { append(" @").append(it.name) }
        if (instruction.type != IrVoid) append(' ').append(instruction.type)
        if (instruction.opcode == Opcode.Phi) {
            append(' ').append(instruction.operands.indices.joinToString { "[${operand(instruction.operands[it])}, ${instruction.targets[it].name}]" })
            return@buildString
        }
        if (instruction.operands.isNotEmpty()) append(' ').append(instruction.operands.joinToString { operand(it) })
        if (instruction.literals.isNotEmpty()) append(" #").append(instruction.literals.joinToString(","))
        if (instruction.targets.isNotEmpty()) append(" -> ").append(instruction.targets.joinToString { it.name })
    }

    private fun operand(value: Value): String = when (value) {
        is Instruction -> value.toString()
        is GlobalVariable -> "@${value.name}"
        is Parameter -> "%${value.name}"
        else -> value.toString()
    }
}
