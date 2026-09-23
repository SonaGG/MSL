package gg.sona.msl.spirv

import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.reflect.DescriptorLocation
import gg.sona.msl.reflect.ResourceBindingRequest

fun interface SpirvBindingLayout {
    fun locate(request: ResourceBindingRequest): DescriptorLocation

    companion object {
        const val CONSTEXPR_SAMPLER_BASE = 16

        val SeparateSets = SpirvBindingLayout { request ->
            when (request.kind) {
                ResourceKind.UniformBuffer, ResourceKind.StorageBuffer -> DescriptorLocation(0, request.mslIndex)
                ResourceKind.SampledTexture, ResourceKind.StorageTexture -> DescriptorLocation(1, request.mslIndex)
                ResourceKind.Sampler -> DescriptorLocation(2, request.mslIndex)
            }
        }

        fun singleSet(set: Int = 0, bufferBase: Int = 0, textureBase: Int = 32, samplerBase: Int = 64) = SpirvBindingLayout { request ->
            when (request.kind) {
                ResourceKind.UniformBuffer, ResourceKind.StorageBuffer -> DescriptorLocation(set, bufferBase + request.mslIndex)
                ResourceKind.SampledTexture, ResourceKind.StorageTexture -> DescriptorLocation(set, textureBase + request.mslIndex)
                ResourceKind.Sampler -> DescriptorLocation(set, samplerBase + request.mslIndex)
            }
        }
    }
}
