package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.ir.Value

class UniformSpecialization(private val specialization: Specialization) {
    fun run(function: IrFunction): Boolean {
        if (specialization.isEmpty) return false
        val replacements = HashMap<Value, Value>()
        for (instruction in function.instructions()) {
            if (instruction.opcode != Opcode.Load) continue
            val field = field(instruction.operands[0]) ?: continue
            val value = specialization.uniforms[field] ?: continue
            replacements[instruction] = constant(instruction.type, value) ?: continue
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun field(pointer: Value): UniformField? {
        val indices = ArrayList<Value>()
        var current = pointer
        while (current is Instruction && current.opcode == Opcode.AccessChain) {
            indices.addAll(0, current.operands.drop(1))
            current = current.operands[0]
        }
        val global = current as? GlobalVariable ?: return null
        val resource = global.resource ?: return null
        if (resource.kind != ResourceKind.UniformBuffer) return null
        val path = StringBuilder()
        var type: IrType = global.valueType
        for (index in indices) {
            val position = (index as? ConstantScalar)?.bits?.toInt() ?: return null
            type = when (type) {
                is IrStruct -> type.members[position].also { path.append(if (path.isEmpty()) "" else ".").append(it.name) }.type
                is IrArray -> type.element.also { path.append('[').append(position).append(']') }
                is IrMatrix -> type.column.also { path.append('[').append(position).append(']') }
                is IrVector -> type.element.also { path.append('.').append(LANES[position]) }
                else -> return null
            }
        }
        return UniformField(resource.mslIndex, path.toString())
    }

    private fun scalars(value: Any): List<Any>? = when (value) {
        is Number, is Boolean -> listOf(value)
        is FloatArray -> value.toList()
        is DoubleArray -> value.toList()
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is BooleanArray -> value.toList()
        is List<*> -> value.map { it ?: return null }
        else -> null
    }

    private fun scalar(type: IrScalar, value: Any): ConstantScalar? = when (type) {
        is IrBool -> ConstantScalar.bool(value as? Boolean ?: ((value as? Number)?.toInt() ?: return null) != 0)
        is IrInt -> ConstantScalar.int(type, (value as? Number)?.toLong() ?: return null)
        is IrFloat -> ConstantScalar.float(type, ConstantFolding.round(type, (value as? Number)?.toDouble() ?: return null))
    }

    private fun constant(type: IrType, value: Any): IrConstant? {
        val flat = scalars(value) ?: return null
        return when (type) {
            is IrScalar -> scalar(type, flat.singleOrNull() ?: return null)
            is IrVector -> if (flat.size == type.count) ConstantComposite(type, flat.map { scalar(type.element, it) ?: return null }) else null
            is IrMatrix -> if (flat.size == type.columns * type.rows) {
                ConstantComposite(type, List(type.columns) { c -> ConstantComposite(type.column, List(type.rows) { r -> scalar(type.column.element, flat[c * type.rows + r]) ?: return null }) })
            } else {
                null
            }

            else -> null
        }
    }

    private companion object {
        const val LANES = "xyzw"
    }
}
