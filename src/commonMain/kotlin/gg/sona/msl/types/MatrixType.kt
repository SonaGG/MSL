package gg.sona.msl.types

class MatrixType private constructor(val element: ScalarType, val columns: Int, val rows: Int) : Type() {
    override val scalarKind: ScalarKind
        get() = element.kind

    val columnType: VectorType
        get() = VectorType.of(element, rows)

    val rowType: VectorType
        get() = VectorType.of(element, columns)

    fun transposed(): MatrixType = of(element, rows, columns)

    override fun toString(): String = "${element.kind.spelling}${columns}x$rows"

    companion object {
        private val instances = Array(ScalarKind.entries.size * 9) { index ->
            MatrixType(ScalarType.of(ScalarKind.entries[index / 9]), index / 3 % 3 + 2, index % 3 + 2)
        }

        fun of(element: ScalarType, columns: Int, rows: Int): MatrixType {
            require(columns in 2..4 && rows in 2..4)
            return instances[element.kind.ordinal * 9 + (columns - 2) * 3 + rows - 2]
        }
    }
}
