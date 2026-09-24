package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.EntryPoint
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.InterfaceInfo
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

object VaryingHoisting {
    private data class Lane(val input: GlobalVariable, val index: Int)

    private class Affine(val terms: Map<Lane, Double>, val constant: Double) {
        fun scale(factor: Double) = Affine(terms.mapValues { it.value * factor }, constant * factor)

        fun plus(other: Affine, sign: Double = 1.0): Affine {
            val combined = HashMap(terms)
            for ((lane, coefficient) in other.terms) combined[lane] = (combined[lane] ?: 0.0) + sign * coefficient
            return Affine(combined.filterValues { it != 0.0 }, constant + sign * other.constant)
        }
    }

    fun run(module: IrModule, vertex: EntryPoint, fragment: EntryPoint): Boolean {
        val inputs = fragment.interfaceVariables.filter { it.storage == StorageClass.Input && it.interfaceInfo?.builtin == null && float(it.valueType) }
        val outputs = vertex.interfaceVariables.filter { it.storage == StorageClass.Output && it.interfaceInfo?.builtin == null }
            .associateBy { it.interfaceInfo!!.location }
        if (inputs.isEmpty()) return false
        val uses = Uses(fragment.function)
        val forms = HashMap<Value, Affine>()
        for (instruction in fragment.function.instructions()) form(instruction, inputs.toSet(), forms)?.let { forms[instruction] = it }
        val frontier = forms.filter { (value, form) ->
            form.terms.isNotEmpty() && value is Instruction && uses.of(value).any { it !in forms || it.opcode == Opcode.Store }
        }
        val absorbed = inputs.filter { input ->
            loads(input, uses).all { load -> load.opcode == Opcode.Load && (load in forms || uses.of(load).all { it in forms }) }
        }
        var removable = absorbed.toSet()
        while (true) {
            val next = removable.filter { input ->
                frontier.values.all { form -> form.terms.keys.none { it.input === input } || form.terms.keys.all { it.input in removable } }
            }.toSet()
            if (next == removable) break
            removable = next
        }
        if (removable.isEmpty()) return false
        val moved = frontier.filter { (_, form) -> form.terms.keys.all { it.input in removable } }
        val removedLanes = removable.sumOf { it.valueType.componentCount }
        if (moved.isEmpty() || moved.size >= removedLanes || moved.size > 4) return false
        val info = removable.first().interfaceInfo!!
        val removed = removable.toList()
        if (removable.any { it.interfaceInfo!!.interpolation != info.interpolation || it.interfaceInfo!!.sampling != info.sampling }) return false
        val stored = HashMap<GlobalVariable, Instruction>()
        for (input in removable) {
            val output = outputs[input.interfaceInfo!!.location] ?: return false
            val stores = Uses(vertex.function).of(output).filter { it.opcode == Opcode.Store }
            if (stores.size != 1 || Uses(vertex.function).of(output).size != 1) return false
            stored[input] = stores.single()
        }
        val anchor = stored.values.firstOrNull { store -> stored.values.all { it.block === store.block } } ?: return false
        val last = stored.values.maxBy { anchor.block!!.instructions.indexOf(it) }
        val element = (removed.first().valueType as? IrVector)?.element ?: removed.first().valueType as IrFloat
        val order = moved.keys.toList()
        val type: IrType = if (order.size == 1) element else IrVector.of(element as IrFloat, order.size)
        val location = (outputs.keys + inputs.map { it.interfaceInfo!!.location }).max() + 1
        val newOutput = GlobalVariable("hoisted.out", type, StorageClass.Output).also { it.interfaceInfo = relocate(info, location, false) }
        val newInput = GlobalVariable("hoisted.in", type, StorageClass.Input).also { it.interfaceInfo = relocate(info, location, true) }

        val vertexBuilder = IrBuilder(vertex.function)
        vertexBuilder.positionBefore(last)
        fun lane(lane: Lane): Value {
            val value = stored.getValue(lane.input).operands[1]
            return if (value.type is IrVector) vertexBuilder.extract(value, lane.index) else value
        }
        val computed = order.map { value ->
            val form = moved.getValue(value)
            var result: Value = ConstantScalar.float(element as IrFloat, form.constant)
            for ((lane, coefficient) in form.terms) {
                val term = vertexBuilder.binary(Opcode.FMul, element, lane(lane), ConstantScalar.float(element, coefficient))
                result = vertexBuilder.binary(Opcode.FAdd, element, result, term)
            }
            result
        }
        vertexBuilder.positionBefore(last)
        vertexBuilder.store(newOutput, if (computed.size == 1) computed[0] else vertexBuilder.construct(type, computed))

        val fragmentBuilder = IrBuilder(fragment.function)
        fragmentBuilder.position(fragment.function.entry)
        fragmentBuilder.positionBefore(fragment.function.entry.instructions.first { it.opcode != Opcode.Phi && it.opcode != Opcode.Variable })
        val loaded = fragmentBuilder.load(newInput)
        val replacements = HashMap<Value, Value>()
        order.forEachIndexed { index, value -> replacements[value] = if (order.size == 1) loaded else fragmentBuilder.extract(loaded, index) }
        IrRewriter.replace(fragment.function, replacements)
        vertex.interfaceVariables.add(newOutput)
        fragment.interfaceVariables.add(newInput)
        module.globals.add(newOutput)
        module.globals.add(newInput)
        return true
    }

    private fun relocate(info: InterfaceInfo, location: Int, input: Boolean) =
        InterfaceInfo(input, location, null, info.interpolation, info.sampling, 0, "hoisted$location", false)

    private fun float(type: IrType): Boolean = type is IrFloat && type.bits == 32 || type is IrVector && (type.element as? IrFloat)?.bits == 32

    private fun loads(input: GlobalVariable, uses: Uses): List<Instruction> = uses.of(input).flatMap { user ->
        when (user.opcode) {
            Opcode.Load -> listOf(user)
            Opcode.AccessChain -> uses.of(user).filter { it.opcode == Opcode.Load }
            else -> listOf(user)
        }
    }

    private fun constant(value: Value): Double? = (value as? ConstantScalar)?.takeIf { it.type is IrFloat }?.asDouble

    private fun form(instruction: Instruction, inputs: Set<GlobalVariable>, forms: Map<Value, Affine>): Affine? {
        val type = instruction.type as? IrFloat ?: return null
        if (type.bits != 32) return null
        fun of(value: Value): Affine? = forms[value] ?: constant(value)?.let { Affine(emptyMap(), it) }
        val ops = instruction.operands
        return when (instruction.opcode) {
            Opcode.CompositeExtract -> {
                val load = ops[0] as? Instruction ?: return null
                val global = load.operands.getOrNull(0) as? GlobalVariable
                if (load.opcode != Opcode.Load || global !in inputs || instruction.literals.size != 1) return null
                Affine(mapOf(Lane(global!!, instruction.literals[0]) to 1.0), 0.0)
            }

            Opcode.Load -> {
                val pointer = ops[0]
                when {
                    pointer is GlobalVariable && pointer in inputs -> Affine(mapOf(Lane(pointer, 0) to 1.0), 0.0)
                    pointer is Instruction && pointer.opcode == Opcode.AccessChain && pointer.operands[0] in inputs ->
                        Affine(mapOf(Lane(pointer.operands[0] as GlobalVariable, (pointer.operands[1] as? ConstantScalar)?.bits?.toInt() ?: return null) to 1.0), 0.0)

                    else -> null
                }
            }

            Opcode.FAdd -> of(ops[0])?.plus(of(ops[1]) ?: return null)
            Opcode.FSub -> of(ops[0])?.plus(of(ops[1]) ?: return null, -1.0)
            Opcode.FNeg -> of(ops[0])?.scale(-1.0)
            Opcode.FMul -> when {
                constant(ops[1]) != null -> of(ops[0])?.scale(constant(ops[1])!!)
                constant(ops[0]) != null -> of(ops[1])?.scale(constant(ops[0])!!)
                else -> null
            }

            Opcode.Intrinsic -> if (instruction.intrinsic == Intrinsic.Fma) {
                val product = when {
                    constant(ops[1]) != null -> of(ops[0])?.scale(constant(ops[1])!!)
                    constant(ops[0]) != null -> of(ops[1])?.scale(constant(ops[0])!!)
                    else -> null
                } ?: return null
                product.plus(of(ops[2]) ?: return null)
            } else {
                null
            }

            else -> null
        }?.takeIf { it.terms.isNotEmpty() }
    }
}
