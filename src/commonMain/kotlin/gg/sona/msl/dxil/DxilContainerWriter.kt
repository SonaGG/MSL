package gg.sona.msl.dxil

import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.llvm.LlvmBitcodeWriter
import gg.sona.msl.util.ByteSink

class DxilContainerWriter(private val emitter: DxilEmitter) {
    fun write(result: DxilModuleResult): ByteArray {
        val parts = ArrayList<Pair<String, ByteArray>>()
        val uavCount = emitter.resources.filter { it.resourceClass == DxilResourceClass.Uav }.sumOf { maxOf(it.count, 1) }
        val features = emitter.features.featureInfo(uavCount)
        val featureSink = ByteSink(8)
        featureSink.writeLongLe(features)
        parts.add("SFI0" to featureSink.toByteArray())
        parts.add("ISG1" to DxilSignatureWriter.write(emitter.inputs, isInput = true))
        parts.add("OSG1" to DxilSignatureWriter.write(emitter.outputs, isInput = false))
        parts.add("PSV0" to DxilPsvWriter(emitter).write())
        parts.add("DXIL" to program(LlvmBitcodeWriter(result.module).write()))
        return container(parts)
    }

    private fun program(bitcode: ByteArray): ByteArray {
        val minor = emitter.shaderModelMinor
        val kind = when (emitter.stage) {
            ShaderStage.Fragment -> 0
            ShaderStage.Vertex -> 1
            ShaderStage.Kernel -> 5
        }
        val sink = ByteSink(bitcode.size + 24)
        sink.writeIntLe((kind shl 16) or (6 shl 4) or minor)
        sink.writeIntLe((24 + bitcode.size + 3) / 4)
        sink.writeAscii("DXIL")
        sink.writeIntLe(0x100 or minor)
        sink.writeIntLe(16)
        sink.writeIntLe(bitcode.size)
        sink.writeBytes(bitcode)
        sink.alignTo(4)
        return sink.toByteArray()
    }

    private fun container(parts: List<Pair<String, ByteArray>>): ByteArray {
        val headerSize = 32 + parts.size * 4
        val sink = ByteSink(headerSize + parts.sumOf { it.second.size + 8 })
        sink.writeAscii("DXBC")
        sink.writeZeros(16)
        sink.writeShortLe(1)
        sink.writeShortLe(0)
        val sizeOffset = sink.size
        sink.writeIntLe(0)
        sink.writeIntLe(parts.size)
        var offset = headerSize
        for ((_, data) in parts) {
            sink.writeIntLe(offset)
            offset += 8 + data.size
        }
        for ((name, data) in parts) {
            sink.writeAscii(name)
            sink.writeIntLe(data.size)
            sink.writeBytes(data)
        }
        sink.patchIntLe(sizeOffset, sink.size)
        val bytes = sink.toByteArray()
        DxilHash.compute(bytes, 20, bytes.size - 20).copyInto(bytes, 4)
        return bytes
    }
}
