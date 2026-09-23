package gg.sona.msl.spirv

class SpirvOptions(
    val version: SpirvVersion = SpirvVersion.V1_5,
    val flipVertexY: Boolean = false,
    val bindings: SpirvBindingLayout = SpirvBindingLayout.SeparateSets,
    val pushConstantBuffers: Set<Int> = emptySet(),
    val emitNames: Boolean = true,
)
