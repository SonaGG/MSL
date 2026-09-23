package gg.sona.msl.ir

class IrVector private constructor(val element: IrScalar, val count: Int) : IrType() {
    override fun equals(other: Any?): Boolean = other is IrVector && other.element == element && other.count == count

    override fun hashCode(): Int = element.hashCode() * 31 + count

    override fun toString(): String = "<$count x $element>"

    companion object {
        fun of(element: IrScalar, count: Int): IrVector {
            require(count in 2..4) { "vector size $count" }
            return IrVector(element, count)
        }
    }
}
