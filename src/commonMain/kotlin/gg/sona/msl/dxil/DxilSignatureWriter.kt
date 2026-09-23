package gg.sona.msl.dxil

import gg.sona.msl.util.ByteSink

object DxilSignatureWriter {
    fun write(elements: List<DxilSignatureElement>, isInput: Boolean): ByteArray {
        val sink = ByteSink()
        sink.writeIntLe(elements.size)
        sink.writeIntLe(8)
        val nameBase = 8 + elements.size * 32
        val names = LinkedHashMap<String, Int>()
        var nameSize = 0
        for (element in elements) {
            names.getOrPut(element.semanticName) {
                (nameBase + nameSize).also { nameSize += element.semanticName.length + 1 }
            }
        }
        for (element in elements) {
            val mask = if (element.isPacked) element.mask else 1
            sink.writeIntLe(0)
            sink.writeIntLe(names.getValue(element.semanticName))
            sink.writeIntLe(element.semanticIndex)
            sink.writeIntLe(systemValue(element.semanticKind))
            sink.writeIntLe(componentType(element.componentType))
            sink.writeIntLe(if (element.isPacked) element.startRow else -1)
            sink.writeByte(mask)
            val used = element.usedMask and mask
            sink.writeByte(if (isInput) used else used.inv() and 0xF)
            sink.writeShortLe(0)
            sink.writeIntLe(0)
        }
        for (name in names.keys) sink.writeAscii(name, nullTerminated = true)
        sink.alignTo(4)
        return sink.toByteArray()
    }

    fun systemValue(kind: Int): Int = when (kind) {
        3 -> 1
        6 -> 2
        7 -> 3
        4 -> 4
        5 -> 5
        1 -> 6
        10 -> 7
        2 -> 8
        13 -> 9
        12 -> 10
        28 -> 23
        16 -> 64
        17 -> 65
        14 -> 66
        19 -> 67
        18 -> 68
        20 -> 69
        else -> 0
    }

    fun componentType(componentType: Int): Int = when (componentType) {
        DxilEmitter.COMPONENT_U32 -> 1
        DxilEmitter.COMPONENT_I32 -> 2
        DxilEmitter.COMPONENT_U16 -> 4
        DxilEmitter.COMPONENT_I16 -> 5
        DxilEmitter.COMPONENT_F16 -> 6
        else -> 3
    }
}
