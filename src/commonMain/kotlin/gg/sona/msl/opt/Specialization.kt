package gg.sona.msl.opt

class Specialization(val uniforms: Map<UniformField, Any> = emptyMap()) {
    val isEmpty: Boolean
        get() = uniforms.isEmpty()
}
