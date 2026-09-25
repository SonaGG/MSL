package gg.sona.msl.ir

class IrImage(
    val dim: ImageDim,
    val sampled: SampledKind,
    val arrayed: Boolean,
    val multisampled: Boolean,
    val depth: Boolean,
    val access: ImageAccess,
    val atomic: Boolean = false,
) : IrType() {
    val texelScalar: IrScalar
        get() = when (sampled) {
            SampledKind.Float -> IrFloat.F32
            SampledKind.Half -> IrFloat.F16
            SampledKind.SInt -> IrInt.I32
            SampledKind.UInt -> IrInt.U32
            SampledKind.SShort -> IrInt.I16
            SampledKind.UShort -> IrInt.U16
        }

    val coordinateCount: Int
        get() = when (dim) {
            ImageDim.Dim1D, ImageDim.Buffer -> 1
            ImageDim.Dim2D -> 2
            ImageDim.Dim3D, ImageDim.Cube -> 3
        }

    fun withAtomic(): IrImage = IrImage(dim, sampled, arrayed, multisampled, depth, access, true)

    override fun equals(other: Any?): Boolean =
        other is IrImage && other.dim == dim && other.sampled == sampled && other.arrayed == arrayed &&
            other.multisampled == multisampled && other.depth == depth && other.access == access && other.atomic == atomic

    override fun hashCode(): Int = listOf(dim, sampled, arrayed, multisampled, depth, access, atomic).hashCode()

    override fun toString(): String = buildString {
        append("image<").append(dim.name.removePrefix("Dim")).append(", ").append(sampled.name.lowercase())
        if (arrayed) append(", array")
        if (multisampled) append(", ms")
        if (depth) append(", depth")
        append(", ").append(access.name.lowercase())
        if (atomic) append(", atomic")
        append('>')
    }
}
