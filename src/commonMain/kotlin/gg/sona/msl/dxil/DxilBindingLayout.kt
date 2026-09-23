package gg.sona.msl.dxil

import gg.sona.msl.reflect.RegisterLocation
import gg.sona.msl.reflect.ResourceBindingRequest

fun interface DxilBindingLayout {
    fun locate(request: ResourceBindingRequest): RegisterLocation

    companion object {
        const val CONSTEXPR_SAMPLER_BASE = 16

        val Default = DxilBindingLayout { request -> RegisterLocation(request.argumentBuffer + 1, request.mslIndex) }
    }
}
