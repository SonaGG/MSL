package gg.sona.msl.dxil

import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.util.ByteSink
import gg.sona.msl.util.IntList

class DxilPsvWriter(private val emitter: DxilEmitter) {
    private val strings = ByteSink()
    private val semanticIndices = IntList()

    fun write(): ByteArray {
        val sink = ByteSink()
        val stage = emitter.stage
        val inputs = emitter.inputs
        val outputs = emitter.outputs
        val state = DxilViewIdState(inputs, outputs)
        strings.writeByte(0)

        sink.writeIntLe(RUNTIME_INFO_SIZE)
        when (stage) {
            ShaderStage.Vertex -> {
                sink.writeByte(if (outputs.any { it.semanticKind == 3 }) 1 else 0)
                sink.writeZeros(15)
            }

            ShaderStage.Fragment -> {
                sink.writeByte(if (outputs.any { it.semanticKind in 17..19 }) 1 else 0)
                sink.writeByte(if (emitter.features.sampleFrequency) 1 else 0)
                sink.writeZeros(14)
            }

            ShaderStage.Kernel -> sink.writeZeros(16)
        }
        sink.writeIntLe(0)
        sink.writeIntLe(-1)
        sink.writeByte(
            when (stage) {
                ShaderStage.Fragment -> 0
                ShaderStage.Vertex -> 1
                ShaderStage.Kernel -> 5
            },
        )
        sink.writeByte(0)
        sink.writeShortLe(0)
        sink.writeByte(inputs.size)
        sink.writeByte(outputs.size)
        sink.writeByte(0)
        sink.writeByte(state.inputVectors)
        sink.writeByte(state.outputVectors)
        sink.writeZeros(3)
        val size = emitter.entry.workgroupSize
        if (stage == ShaderStage.Kernel) {
            sink.writeIntLe(size.x)
            sink.writeIntLe(size.y)
            sink.writeIntLe(size.z)
        } else {
            sink.writeZeros(12)
        }
        val entryNameOffset = sink.size
        sink.writeIntLe(0)

        val resources = ORDER.flatMap { resourceClass -> emitter.resources.filter { it.resourceClass == resourceClass }.sortedBy { it.id } }
        sink.writeIntLe(resources.size)
        if (resources.isNotEmpty()) {
            sink.writeIntLe(24)
            for (resource in resources) {
                sink.writeIntLe(resourceType(resource))
                sink.writeIntLe(resource.space)
                sink.writeIntLe(resource.register)
                sink.writeIntLe(if (resource.count <= 0) -1 else resource.register + resource.count - 1)
                sink.writeIntLe(resource.kind)
                sink.writeIntLe(0)
            }
        }

        val elements = ByteSink()
        for (element in inputs + outputs) writeElement(elements, element)
        sink.patchIntLe(entryNameOffset, strings.size)
        strings.writeAscii(emitter.entry.name, nullTerminated = true)
        strings.alignTo(4)
        sink.writeIntLe(strings.size)
        sink.writeBytes(strings.toByteArray())
        sink.writeIntLe(semanticIndices.size)
        for (i in 0 until semanticIndices.size) sink.writeIntLe(semanticIndices[i])
        if (inputs.isNotEmpty() || outputs.isNotEmpty()) {
            sink.writeIntLe(ELEMENT_SIZE)
            sink.writeBytes(elements.toByteArray())
        }
        if (stage != ShaderStage.Kernel && state.inputVectors > 0 && state.outputVectors > 0) {
            for (value in state.dependencies) sink.writeIntLe(value)
        }
        return sink.toByteArray()
    }

    private fun writeElement(sink: ByteSink, element: DxilSignatureElement) {
        val nameOffset = if (element.semanticKind == 0) {
            strings.size.also { strings.writeAscii(element.semanticName, nullTerminated = true) }
        } else {
            0
        }
        sink.writeIntLe(nameOffset)
        sink.writeIntLe(semanticIndexOffset(element.semanticIndex))
        sink.writeByte(1)
        if (element.isPacked) {
            sink.writeByte(element.startRow)
            sink.writeByte(element.columns or (element.startColumn shl 4) or 0x40)
        } else {
            sink.writeByte(0)
            sink.writeByte(element.columns)
        }
        sink.writeByte(element.semanticKind)
        sink.writeByte(DxilSignatureWriter.componentType(element.componentType))
        sink.writeByte(element.interpolation)
        sink.writeByte(0)
        sink.writeByte(0)
    }

    private fun semanticIndexOffset(index: Int): Int {
        for (i in 0 until semanticIndices.size) if (semanticIndices[i] == index) return i
        semanticIndices.add(index)
        return semanticIndices.size - 1
    }

    private fun resourceType(resource: DxilResource): Int = when (resource.resourceClass) {
        DxilResourceClass.Sampler -> 1
        DxilResourceClass.CBuffer -> 2
        DxilResourceClass.Srv -> if (resource.isRaw) 4 else 3
        DxilResourceClass.Uav -> if (resource.isRaw) 7 else 6
    }

    private companion object {
        const val RUNTIME_INFO_SIZE = 52
        const val ELEMENT_SIZE = 16
        val ORDER = listOf(DxilResourceClass.CBuffer, DxilResourceClass.Sampler, DxilResourceClass.Srv, DxilResourceClass.Uav)
    }
}
