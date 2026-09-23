package gg.sona.msl.ir

class InterfaceInfo(
    val isInput: Boolean,
    val location: Int,
    val builtin: BuiltinVariable?,
    val interpolation: Interpolation = Interpolation.Perspective,
    val sampling: Sampling = Sampling.Center,
    val index: Int = 0,
    val name: String = "",
    val invariant: Boolean = false,
) {
    val isBuiltin: Boolean
        get() = builtin != null
}
