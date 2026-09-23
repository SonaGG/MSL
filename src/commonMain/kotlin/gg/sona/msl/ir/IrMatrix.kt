package gg.sona.msl.ir

class IrMatrix(val column: IrVector, val columns: Int) : IrType() {
    val rows: Int
        get() = column.count

    override fun equals(other: Any?): Boolean = other is IrMatrix && other.column == column && other.columns == columns

    override fun hashCode(): Int = column.hashCode() * 31 + columns

    override fun toString(): String = "mat${columns}x${rows}<${column.element}>"
}
