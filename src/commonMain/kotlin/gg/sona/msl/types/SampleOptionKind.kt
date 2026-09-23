package gg.sona.msl.types

enum class SampleOptionKind(val spelling: String) {
    Bias("bias"),
    Level("level"),
    Gradient2D("gradient2d"),
    Gradient3D("gradient3d"),
    GradientCube("gradientcube"),
    MinLodClamp("min_lod_clamp"),
    ;

    companion object {
        fun fromSpelling(spelling: String): SampleOptionKind? = entries.firstOrNull { it.spelling == spelling }
    }
}
