package gg.sona.msl.types

enum class TextureKind(
    val spelling: String,
    val dimensions: Int,
    val isArray: Boolean,
    val isCube: Boolean,
    val isMultisampled: Boolean,
    val isDepth: Boolean,
    val isBuffer: Boolean = false,
) {
    Texture1D("texture1d", 1, false, false, false, false),
    Texture1DArray("texture1d_array", 1, true, false, false, false),
    Texture2D("texture2d", 2, false, false, false, false),
    Texture2DArray("texture2d_array", 2, true, false, false, false),
    Texture3D("texture3d", 3, false, false, false, false),
    TextureCube("texturecube", 2, false, true, false, false),
    TextureCubeArray("texturecube_array", 2, true, true, false, false),
    Texture2DMS("texture2d_ms", 2, false, false, true, false),
    Texture2DMSArray("texture2d_ms_array", 2, true, false, true, false),
    TextureBuffer("texture_buffer", 1, false, false, false, false, true),
    Depth2D("depth2d", 2, false, false, false, true),
    Depth2DArray("depth2d_array", 2, true, false, false, true),
    DepthCube("depthcube", 2, false, true, false, true),
    DepthCubeArray("depthcube_array", 2, true, true, false, true),
    Depth2DMS("depth2d_ms", 2, false, false, true, true),
    Depth2DMSArray("depth2d_ms_array", 2, true, false, true, true),
    ;

    val coordinateCount: Int
        get() = if (isCube) 3 else dimensions

    val supportsMipmaps: Boolean
        get() = !isMultisampled && !isBuffer

    companion object {
        fun fromSpelling(spelling: String): TextureKind? = entries.firstOrNull { it.spelling == spelling }
    }
}
