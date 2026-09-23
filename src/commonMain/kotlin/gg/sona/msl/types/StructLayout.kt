package gg.sona.msl.types

class StructLayout(val offsets: IntArray, val size: Int, val alignment: Int) {
    companion object {
        fun compute(struct: StructType): StructLayout {
            val offsets = IntArray(struct.fields.size)
            var offset = 0
            var alignment = maxOf(1, struct.explicitAlignment)
            var size = 0
            for ((index, field) in struct.fields.withIndex()) {
                val fieldAlignment = maxOf(TypeLayout.alignOf(field.type), field.explicitAlignment)
                val fieldSize = TypeLayout.sizeOf(field.type)
                alignment = maxOf(alignment, fieldAlignment)
                if (struct.isUnion) {
                    offsets[index] = 0
                    size = maxOf(size, fieldSize)
                } else {
                    offset = TypeLayout.alignUp(offset, fieldAlignment)
                    offsets[index] = offset
                    offset += fieldSize
                    size = offset
                }
            }
            return StructLayout(offsets, TypeLayout.alignUp(size, alignment), alignment)
        }
    }
}
