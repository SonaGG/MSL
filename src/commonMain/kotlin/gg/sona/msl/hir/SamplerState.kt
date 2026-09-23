package gg.sona.msl.hir

data class SamplerState(
    val normalizedCoordinates: Boolean = true,
    val addressU: SamplerAddressMode = SamplerAddressMode.ClampToEdge,
    val addressV: SamplerAddressMode = SamplerAddressMode.ClampToEdge,
    val addressW: SamplerAddressMode = SamplerAddressMode.ClampToEdge,
    val magFilter: SamplerFilter = SamplerFilter.Nearest,
    val minFilter: SamplerFilter = SamplerFilter.Nearest,
    val mipFilter: SamplerMipFilter = SamplerMipFilter.None,
    val compareFunction: SamplerCompareFunction = SamplerCompareFunction.Never,
    val borderColor: SamplerBorderColor = SamplerBorderColor.TransparentBlack,
    val maxAnisotropy: Int = 1,
    val lodMin: Float = 0f,
    val lodMax: Float = Float.MAX_VALUE,
)
