package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.TextureOperands
import gg.sona.msl.passes.Uniformity

object ExplicitLevelSampling {
    private val IMPLICIT_BLOCKERS = listOf(TextureOperands.Bias, TextureOperands.Lod, TextureOperands.Gradient, TextureOperands.MinLod)

    fun run(function: IrFunction): Boolean {
        var uniformity: Uniformity? = null
        var changed = false
        for (instruction in function.instructions()) {
            if (instruction.opcode != Opcode.Intrinsic || instruction.intrinsic != Intrinsic.TextureSample) continue
            val flags = TextureOperands(instruction.literals[0])
            if (IMPLICIT_BLOCKERS.any { it in flags }) continue
            val analysis = uniformity ?: Uniformity(function).also { uniformity = it }
            val array = if (TextureOperands.ArrayIndex in flags) 1 else 0
            val coordinates = instruction.operands.subList(2, 3 + array)
            if (!coordinates.all(analysis::isUniform)) continue
            instruction.operands.add(3 + array, ConstantScalar.f32(0f))
            instruction.literals = instruction.literals.copyOf().also { it[0] = (flags + TextureOperands.Lod).bits }
            changed = true
        }
        return changed
    }
}
