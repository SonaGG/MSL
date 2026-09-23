package gg.sona.msl.dxil

class DxilFeatures {
    var minimumShaderModel = 0
        private set
    var rawBuffers = false
    var int64 = false
    var doubles = false
    var waveOps = false
    var viewportAndLayer = false
    var stencilRef = false
    var barycentrics = false
    var uavsAtEveryStage = false
    var sampleFrequency = false
    var typedUavLoads = false
    var lowPrecision = false

    fun require(minor: Int) {
        minimumShaderModel = maxOf(minimumShaderModel, minor)
    }

    fun shaderFlags(earlyDepth: Boolean, uavCount: Int): Long {
        var flags = 0L
        if (doubles) flags = flags or (1L shl 2)
        if (earlyDepth) flags = flags or (1L shl 3)
        if (rawBuffers) flags = flags or (1L shl 4)
        if (lowPrecision) flags = flags or (1L shl 5) or (1L shl 23)
        if (viewportAndLayer) flags = flags or (1L shl 9)
        if (stencilRef) flags = flags or (1L shl 11)
        if (typedUavLoads) flags = flags or (1L shl 13)
        if (uavCount > 8) flags = flags or (1L shl 15)
        if (uavsAtEveryStage) flags = flags or (1L shl 16)
        if (waveOps) flags = flags or (1L shl 19)
        if (int64) flags = flags or (1L shl 20)
        if (barycentrics) flags = flags or (1L shl 22)
        return flags
    }

    fun featureInfo(uavCount: Int): Long {
        var flags = 0L
        if (doubles) flags = flags or 0x1L
        if (uavsAtEveryStage) flags = flags or 0x4L
        if (uavCount > 8) flags = flags or 0x8L
        if (lowPrecision) flags = flags or 0x40000L
        if (stencilRef) flags = flags or 0x200L
        if (typedUavLoads) flags = flags or 0x800L
        if (viewportAndLayer) flags = flags or 0x2000L
        if (waveOps) flags = flags or 0x4000L
        if (int64) flags = flags or 0x8000L
        if (barycentrics) flags = flags or 0x20000L
        return flags
    }
}
