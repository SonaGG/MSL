package gg.sona.msl.hir

enum class TextureOperation {
    Sample,
    SampleCompare,
    Gather,
    GatherCompare,
    Read,
    Write,
    GetWidth,
    GetHeight,
    GetDepth,
    GetArraySize,
    GetNumMipLevels,
    GetNumSamples,
    CalculateClampedLod,
    CalculateUnclampedLod,
    Fence,
}
