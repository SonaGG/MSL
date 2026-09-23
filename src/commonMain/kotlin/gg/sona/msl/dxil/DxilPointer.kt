package gg.sona.msl.dxil

import gg.sona.msl.ir.IrType

sealed class DxilPointer {
    abstract val type: IrType
}
