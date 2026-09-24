package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Undef
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.passes.Uses

object StageLinking {
    fun run(module: IrModule, links: List<StageLink>): Set<IrFunction> {
        val changed = HashSet<IrFunction>()
        for (link in links) {
            val vertex = module.entryPoints.firstOrNull { it.name == link.vertex && it.stage == ShaderStage.Vertex } ?: continue
            val fragment = module.entryPoints.firstOrNull { it.name == link.fragment && it.stage == ShaderStage.Fragment } ?: continue
            if (link(module, vertex, fragment)) {
                changed.add(vertex.function)
                changed.add(fragment.function)
            }
        }
        return changed
    }

    private fun varyings(entry: EntryPoint, storage: StorageClass): Map<Int, GlobalVariable> =
        entry.interfaceVariables.filter { it.storage == storage && it.interfaceInfo != null && it.interfaceInfo!!.builtin == null }
            .associateBy { it.interfaceInfo!!.location }

    private fun link(module: IrModule, vertex: EntryPoint, fragment: EntryPoint): Boolean {
        val outputs = varyings(vertex, StorageClass.Output)
        val inputs = varyings(fragment, StorageClass.Input)
        var changed = false
        for ((location, output) in outputs) {
            val vertexUses = Uses(vertex.function)
            val fragmentUses = Uses(fragment.function)
            val input = inputs[location]
            val loads = input?.let { loads(it, fragmentUses) }
            val stores = stores(output, vertexUses)
            when {
                loads.isNullOrEmpty() -> {
                    stores.forEach { it.block!!.instructions.remove(it) }
                    remove(module, vertex, output)
                    if (input != null) remove(module, fragment, input)
                    changed = true
                }

                stores.size == 1 && stores[0].operands[0] === output && stores[0].operands[1] is IrConstant -> {
                    val constant = stores[0].operands[1] as IrConstant
                    val replacements = HashMap<Value, Value>()
                    for (load in loads) {
                        val lanes = path(load.operands[0]) ?: continue
                        val builder = IrBuilder(fragment.function)
                        builder.positionBefore(load)
                        replacements[load] = if (lanes.isEmpty()) constant else builder.extract(constant, *lanes.toIntArray())
                    }
                    if (replacements.size != loads.size) continue
                    IrRewriter.replace(fragment.function, replacements)
                    stores[0].block!!.instructions.remove(stores[0])
                    remove(module, vertex, output)
                    remove(module, fragment, input)
                    changed = true
                }

                else -> changed = narrow(module, vertex, fragment, output, input, stores, loads, fragmentUses) || changed
            }
        }
        return changed
    }

    private fun path(pointer: Value): List<Int>? {
        if (pointer is GlobalVariable) return emptyList()
        val chain = pointer as? Instruction ?: return null
        if (chain.opcode != Opcode.AccessChain || chain.operands[0] !is GlobalVariable) return null
        return chain.operands.drop(1).map { (it as? ConstantScalar)?.bits?.toInt() ?: return null }
    }

    private fun loads(global: GlobalVariable, uses: Uses): List<Instruction> = uses.of(global).flatMap { user ->
        when (user.opcode) {
            Opcode.Load -> listOf(user)
            Opcode.AccessChain -> uses.of(user).filter { it.opcode == Opcode.Load }
            else -> emptyList()
        }
    }

    private fun stores(global: GlobalVariable, uses: Uses): List<Instruction> = uses.of(global).flatMap { user ->
        when (user.opcode) {
            Opcode.Store -> listOf(user)
            Opcode.AccessChain -> uses.of(user).filter { it.opcode == Opcode.Store }
            else -> emptyList()
        }
    }

    private fun remove(module: IrModule, entry: EntryPoint, global: GlobalVariable) {
        entry.interfaceVariables.remove(global)
        if (module.entryPoints.none { global in it.interfaceVariables }) module.globals.remove(global)
    }

    private fun narrow(
        module: IrModule,
        vertex: EntryPoint,
        fragment: EntryPoint,
        output: GlobalVariable,
        input: GlobalVariable,
        stores: List<Instruction>,
        loads: List<Instruction>,
        uses: Uses,
    ): Boolean {
        val type = input.valueType as? IrVector ?: return false
        if (output.valueType != type || stores.any { it.operands[0] !== output }) return false
        val lanes = sortedSetOf<Int>()
        for (load in loads) {
            val path = path(load.operands[0]) ?: return false
            when (path.size) {
                1 -> lanes.add(path[0])
                0 -> for (user in uses.of(load)) {
                    when (user.opcode) {
                        Opcode.CompositeExtract -> if (user.literals.size == 1) lanes.add(user.literals[0]) else return false
                        Opcode.VectorShuffle -> {
                            val width = user.operands[0].type.componentCount
                            for (lane in user.literals) {
                                if (lane < width && user.operands[0] === load) lanes.add(lane)
                                if (lane >= width && user.operands[1] === load) lanes.add(lane - width)
                            }
                        }

                        else -> return false
                    }
                }

                else -> return false
            }
        }
        if (lanes.size >= type.count) return false
        val order = lanes.toList()
        val narrowed = if (order.size == 1) type.element else IrVector.of(type.element, order.size)
        val newOutput = GlobalVariable(output.name, narrowed, StorageClass.Output).also { it.interfaceInfo = output.interfaceInfo }
        val newInput = GlobalVariable(input.name, narrowed, StorageClass.Input).also { it.interfaceInfo = input.interfaceInfo }
        val vertexBuilder = IrBuilder(vertex.function)
        for (store in stores) {
            vertexBuilder.positionBefore(store)
            val value = store.operands[1]
            store.operands[1] = if (order.size == 1) vertexBuilder.extract(value, order[0]) else vertexBuilder.shuffle(value, value, order.toIntArray())
            store.operands[0] = newOutput
        }
        val fragmentBuilder = IrBuilder(fragment.function)
        val replacements = HashMap<Value, Value>()
        for (load in loads) {
            val path = path(load.operands[0])!!
            fragmentBuilder.positionBefore(load)
            val loaded = fragmentBuilder.load(newInput)
            fun lane(index: Int): Value = if (order.size == 1) loaded else fragmentBuilder.extract(loaded, order.indexOf(index))
            replacements[load] = if (path.size == 1) {
                lane(path[0])
            } else {
                fragmentBuilder.construct(type, List(type.count) { if (it in lanes) lane(it) else Undef(type.element) })
            }
        }
        IrRewriter.replace(fragment.function, replacements)
        replace(module, vertex, output, newOutput)
        replace(module, fragment, input, newInput)
        return true
    }

    private fun replace(module: IrModule, entry: EntryPoint, old: GlobalVariable, new: GlobalVariable) {
        val index = entry.interfaceVariables.indexOf(old)
        entry.interfaceVariables[index] = new
        module.globals.add(new)
        if (module.entryPoints.none { old in it.interfaceVariables }) module.globals.remove(old)
    }
}
