package gg.sona.msl.opt

import gg.sona.msl.ir.IrConstant

class LatticeValue private constructor(val constant: IrConstant?, val isOverdefined: Boolean) {
    val isUnknown: Boolean
        get() = constant == null && !isOverdefined

    fun meet(other: LatticeValue): LatticeValue = when {
        other.isUnknown -> this
        isUnknown -> other
        isOverdefined || other.isOverdefined -> Overdefined
        constant == other.constant -> this
        else -> Overdefined
    }

    companion object {
        val Unknown = LatticeValue(null, false)
        val Overdefined = LatticeValue(null, true)

        fun of(constant: IrConstant) = LatticeValue(constant, false)
    }
}
