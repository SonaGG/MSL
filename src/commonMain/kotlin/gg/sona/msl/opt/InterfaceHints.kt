package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.passes.Uses
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation

object InterfaceHints {
    private const val LANES = "xyzw"

    fun report(entry: EntryPoint, diagnostics: Diagnostics) {
        val uses = Uses(entry.function)
        val prefix = "${entry.stage.keyword} function '${entry.name}'"
        for (global in entry.interfaceVariables) {
            val info = global.interfaceInfo
            val name = info?.name?.ifEmpty { null } ?: global.name
            when {
                global.resource != null -> if (!referenced(global, uses)) {
                    hint(diagnostics, "$prefix: resource '${global.name}' is bound but never used; removing it would free a binding slot")
                }

                global.storage == StorageClass.Input && info != null && info.builtin == null -> input(global, name, uses, prefix, diagnostics)
                global.storage == StorageClass.Output && info != null -> output(global, name, uses, prefix, diagnostics)
            }
        }
    }

    private fun hint(diagnostics: Diagnostics, message: String) = diagnostics.performanceHint(SourceLocation.NONE, message)

    private fun referenced(global: GlobalVariable, uses: Uses): Boolean = uses.isUsed(global)

    private fun loads(pointer: gg.sona.msl.ir.Value, uses: Uses): List<Instruction> = uses.of(pointer).flatMap { user ->
        when (user.opcode) {
            Opcode.Load -> listOf(user)
            Opcode.AccessChain -> if (user.operands[0] === pointer) loads(user, uses) else emptyList()
            else -> emptyList()
        }
    }

    private fun input(global: GlobalVariable, name: String, uses: Uses, prefix: String, diagnostics: Diagnostics) {
        val loads = loads(global, uses)
        val live = loads.filter { uses.isUsed(it) }
        if (live.isEmpty()) {
            hint(diagnostics, "$prefix: input '$name' is never read; removing it from the interface would save an interpolant/attribute fetch")
            return
        }
        val type = global.valueType as? IrVector ?: return
        val lanes = HashSet<Int>()
        for (load in live) {
            val pointer = load.operands[0]
            if (pointer !== global) {
                val chain = pointer as? Instruction ?: return
                val lane = (chain.operands.getOrNull(1) as? ConstantScalar)?.bits?.toInt() ?: return
                if (chain.opcode != Opcode.AccessChain || chain.operands.size != 2) return
                lanes.add(lane)
                continue
            }
            for (user in uses.of(load)) {
                when (user.opcode) {
                    Opcode.CompositeExtract -> if (user.literals.size == 1) lanes.add(user.literals[0]) else return
                    Opcode.VectorShuffle -> user.literals.forEach { lane ->
                        if (user.operands[0] === load && lane < type.count) lanes.add(lane)
                        if (user.operands[1] === load && lane >= type.count) lanes.add(lane - type.count)
                    }

                    else -> return
                }
            }
        }
        if (lanes.size < type.count) {
            val used = lanes.sorted().joinToString("") { LANES[it].toString() }
            hint(diagnostics, "$prefix: only components .$used of input '$name' (${type.count} components) are read; narrowing it would reduce interpolation cost")
        }
    }

    private fun output(global: GlobalVariable, name: String, uses: Uses, prefix: String, diagnostics: Diagnostics) {
        val stores = uses.of(global).filter { it.opcode == Opcode.Store && it.operands[0] === global }
        val partial = uses.of(global).any { it.opcode == Opcode.AccessChain }
        if (stores.isEmpty() && !partial) {
            hint(diagnostics, "$prefix: output '$name' is never written; its value is undefined and it could be removed")
            return
        }
        if (partial) return
        val values = stores.map { it.operands[1] }.distinct()
        val constant = values.singleOrNull() as? IrConstant ?: return
        hint(diagnostics, "$prefix: output '$name' is always $constant; the consumer could use the constant directly and drop this varying")
    }
}
