package gg.sona.msl.ir

sealed class IrType {
    open val isScalar: Boolean
        get() = false

    val isInteger: Boolean
        get() = this is IrInt || (this is IrVector && element is IrInt)

    val isFloat: Boolean
        get() = this is IrFloat || (this is IrVector && element is IrFloat)

    val isBool: Boolean
        get() = this is IrBool || (this is IrVector && element is IrBool)

    val scalar: IrScalar
        get() = when (this) {
            is IrScalar -> this
            is IrVector -> element
            is IrMatrix -> column.element
            else -> error("$this has no scalar element")
        }

    val componentCount: Int
        get() = if (this is IrVector) count else 1
}
