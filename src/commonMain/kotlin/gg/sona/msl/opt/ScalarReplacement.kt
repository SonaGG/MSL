package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.Opcode
import gg.sona.msl.passes.Uses

class ScalarReplacement(private val maxElements: Int) {
    fun run(function: IrFunction): Boolean {
        var changed = false
        while (true) {
            val uses = Uses(function)
            val candidate = function.entry.instructions.firstOrNull { it.opcode == Opcode.Variable && splittable(it, uses) } ?: return changed
            split(function, candidate, uses)
            changed = true
        }
    }

    private fun members(type: IrType): List<IrType>? = when (type) {
        is IrStruct -> type.members.map { it.type }
        is IrArray -> if (!type.isRuntime && type.length in 1..maxElements) List(type.length) { type.element } else null
        else -> null
    }

    private fun opaque(type: IrType): Boolean = when (type) {
        is IrImage, IrSampler -> true
        is IrArray -> opaque(type.element)
        is IrStruct -> type.members.any { opaque(it.type) }
        else -> false
    }

    private fun splittable(variable: Instruction, uses: Uses): Boolean {
        val pointee = (variable.type as IrPointer).pointee
        if (members(pointee) == null || opaque(pointee)) return false
        return uses.of(variable).all { user ->
            when (user.opcode) {
                Opcode.Load -> true
                Opcode.Store -> user.operands[0] === variable && user.operands[1] !== variable
                Opcode.AccessChain -> user.operands[0] === variable && user.operands.size >= 2 &&
                    (user.operands[1] as? ConstantScalar)?.bits?.let { it >= 0 && it < members(pointee)!!.size } == true &&
                    plainPointer(user, uses)
                else -> false
            }
        }
    }

    private fun plainPointer(pointer: Instruction, uses: Uses): Boolean = uses.of(pointer).all { user ->
        when (user.opcode) {
            Opcode.Load -> true
            Opcode.Store -> user.operands[0] === pointer && user.operands[1] !== pointer
            Opcode.AccessChain -> user.operands[0] === pointer && plainPointer(user, uses)
            else -> false
        }
    }

    private fun split(function: IrFunction, variable: Instruction, uses: Uses) {
        val pointee = (variable.type as IrPointer).pointee
        val types = members(pointee)!!
        val builder = IrBuilder(function)
        val parts = types.map { type -> builder.variable(type) }
        for (user in uses.of(variable)) {
            when (user.opcode) {
                Opcode.Load -> {
                    builder.positionBefore(user)
                    val values = parts.map { builder.load(it) }
                    user.opcode = Opcode.CompositeConstruct
                    user.operands.clear()
                    user.operands.addAll(values)
                }

                Opcode.Store -> {
                    builder.positionBefore(user)
                    val value = user.operands[1]
                    parts.forEachIndexed { index, part -> builder.store(part, builder.extract(value, index)) }
                    user.block!!.instructions.remove(user)
                }

                else -> {
                    val index = (user.operands[1] as ConstantScalar).bits.toInt()
                    val part = parts[index]
                    if (user.operands.size == 2) {
                        val replacements = mapOf<gg.sona.msl.ir.Value, gg.sona.msl.ir.Value>(user to part)
                        user.block!!.instructions.remove(user)
                        IrRewriter.replace(function, replacements)
                    } else {
                        user.operands.removeAt(1)
                        user.operands[0] = part
                    }
                }
            }
        }
        variable.block!!.instructions.remove(variable)
    }
}
