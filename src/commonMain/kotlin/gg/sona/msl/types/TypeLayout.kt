package gg.sona.msl.types

object TypeLayout {
    fun alignUp(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    fun sizeOf(type: Type): Int = when (type) {
        is ScalarType -> type.kind.byteSize
        is VectorType -> if (type.packed) type.size * type.element.kind.byteSize else vectorAllocation(type)
        is MatrixType -> type.columns * sizeOf(type.columnType)
        is ArrayType -> if (type.isUnsized) 0 else type.size * stride(type.element)
        is StructType -> type.layout().size
        is AtomicType -> type.element.kind.byteSize
        is EnumType -> type.underlying.kind.byteSize
        is PointerType -> 8
        is TextureType, is SamplerType -> 8
        else -> 0
    }

    fun alignOf(type: Type): Int = when (type) {
        is ScalarType -> type.kind.byteSize
        is VectorType -> if (type.packed) type.element.kind.byteSize else vectorAllocation(type)
        is MatrixType -> alignOf(type.columnType)
        is ArrayType -> alignOf(type.element)
        is StructType -> type.layout().alignment
        is AtomicType -> type.element.kind.byteSize
        is EnumType -> type.underlying.kind.byteSize
        is PointerType -> 8
        is TextureType, is SamplerType -> 8
        else -> 1
    }

    fun stride(type: Type): Int = alignUp(sizeOf(type), alignOf(type))

    private fun vectorAllocation(type: VectorType): Int {
        val lanes = if (type.size == 3) 4 else type.size
        return lanes * type.element.kind.byteSize
    }
}
