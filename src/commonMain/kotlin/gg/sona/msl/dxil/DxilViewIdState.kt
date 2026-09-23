package gg.sona.msl.dxil

class DxilViewIdState(inputs: List<DxilSignatureElement>, outputs: List<DxilSignatureElement>) {
    val inputVectors: Int = vectors(inputs)
    val outputVectors: Int = vectors(outputs)
    val inputScalars: Int = inputVectors * 4
    val outputScalars: Int = outputVectors * 4
    val maskDwords: Int = (outputScalars + 31) / 32
    val dependencies: IntArray = IntArray(inputScalars * maskDwords)

    init {
        val written = IntArray(maskDwords)
        for (output in outputs) {
            if (!output.isPacked) continue
            for (column in 0 until 4) {
                if (output.usedMask and (1 shl column) == 0) continue
                val scalar = output.startRow * 4 + column
                written[scalar / 32] = written[scalar / 32] or (1 shl (scalar % 32))
            }
        }
        for (input in inputs) {
            if (!input.isPacked) continue
            for (column in 0 until 4) {
                if (input.usedMask and (1 shl column) == 0) continue
                val scalar = input.startRow * 4 + column
                written.copyInto(dependencies, scalar * maskDwords)
            }
        }
    }

    fun serialize(): IntArray = intArrayOf(inputScalars, outputScalars) + dependencies

    private companion object {
        fun vectors(elements: List<DxilSignatureElement>): Int =
            elements.filter { it.isPacked }.maxOfOrNull { it.startRow + 1 } ?: 0
    }
}
