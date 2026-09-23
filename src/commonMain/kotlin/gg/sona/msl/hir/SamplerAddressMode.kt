package gg.sona.msl.hir

enum class SamplerAddressMode(val spelling: String) {
    ClampToEdge("clamp_to_edge"),
    ClampToZero("clamp_to_zero"),
    ClampToBorder("clamp_to_border"),
    Repeat("repeat"),
    MirroredRepeat("mirrored_repeat"),
}
