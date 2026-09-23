package gg.sona.msl.types

class TextureType(val kind: TextureKind, val sampleType: ScalarType, val access: TextureAccess) : Type() {
    val texelType: VectorType
        get() = VectorType.of(sampleType, 4)

    override fun equals(other: Any?): Boolean =
        other is TextureType && other.kind == kind && other.sampleType == sampleType && other.access == access

    override fun hashCode(): Int = (kind.hashCode() * 31 + sampleType.hashCode()) * 31 + access.hashCode()

    override fun toString(): String = "${kind.spelling}<$sampleType, access::${access.spelling}>"
}
