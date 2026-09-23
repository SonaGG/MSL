package gg.sona.msl.dxil

import gg.sona.msl.ir.GlobalVariable
import gg.sona.msl.ir.IrType

class InterfacePointer(
    val variable: GlobalVariable,
    val component: Int,
    override val type: IrType,
) : DxilPointer()
