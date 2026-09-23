package gg.sona.msl.types

class VectorType private constructor(val element: ScalarType, val size: Int, val packed: Boolean) : Type() {
    override val isVector: Boolean
        get() = true

    override val scalarKind: ScalarKind
        get() = element.kind

    fun unpacked(): VectorType = of(element, size)

    override fun toString(): String = (if (packed) "packed_" else "") + element.kind.spelling + size

    companion object {
        private val instances = Array(ScalarKind.entries.size * 3 * 2) { index ->
            val kind = ScalarKind.entries[index / 6]
            VectorType(ScalarType.of(kind), index % 3 + 2, (index / 3) % 2 == 1)
        }

        fun of(element: ScalarType, size: Int, packed: Boolean = false): VectorType {
            require(size in 2..4) { "vector size $size" }
            return instances[element.kind.ordinal * 6 + (if (packed) 3 else 0) + size - 2]
        }

        fun of(kind: ScalarKind, size: Int, packed: Boolean = false): VectorType = of(ScalarType.of(kind), size, packed)
    }
}
