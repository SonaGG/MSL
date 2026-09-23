package gg.sona.msl.ir

object Constants {
    fun zero(type: IrType): IrConstant = when (type) {
        is IrBool -> ConstantScalar.bool(false)
        is IrInt -> ConstantScalar.int(type, 0)
        is IrFloat -> ConstantScalar.float(type, 0.0)
        else -> ConstantNull(type)
    }

    fun one(type: IrScalar): ConstantScalar = scalar(type, 1.0)

    fun scalar(type: IrScalar, value: Double): ConstantScalar = when (type) {
        is IrBool -> ConstantScalar.bool(value != 0.0)
        is IrInt -> ConstantScalar.int(type, value.toLong())
        is IrFloat -> ConstantScalar.float(type, if (type.bits == 32) value.toFloat().toDouble() else value)
    }

    fun integer(type: IrScalar, value: Long): ConstantScalar = when (type) {
        is IrBool -> ConstantScalar.bool(value != 0L)
        is IrInt -> ConstantScalar.int(type, value)
        is IrFloat -> ConstantScalar.float(type, value.toDouble())
    }

    fun splat(type: IrType, scalar: ConstantScalar): IrConstant =
        if (type is IrVector) ConstantComposite(type, List(type.count) { scalar }) else scalar

    fun isZero(value: Value): Boolean = when (value) {
        is ConstantNull -> true
        is ConstantScalar -> value.bits == 0L || (value.type is IrFloat && value.asDouble == 0.0)
        is ConstantComposite -> value.elements.all { isZero(it) }
        else -> false
    }

    fun scalars(value: IrConstant): List<IrConstant> = when (value) {
        is ConstantComposite -> value.elements
        is ConstantNull -> List(value.type.componentCount) { zero(value.type.scalar) }
        is Undef -> List(value.type.componentCount) { Undef(value.type.scalar) }
        else -> listOf(value)
    }
}
