package gg.sona.msl.dxil

class DxilSignatureElement(
    val id: Int,
    val semanticName: String,
    val semanticIndex: Int,
    val componentType: Int,
    val semanticKind: Int,
    val interpolation: Int,
    val columns: Int,
    val startRow: Int,
    val startColumn: Int,
    val isInput: Boolean,
    val isSystemValue: Boolean,
) {
    var usedMask = 0

    val isPacked: Boolean
        get() = startRow >= 0

    val mask: Int
        get() = ((1 shl columns) - 1) shl (if (startColumn < 0) 0 else startColumn)
}
