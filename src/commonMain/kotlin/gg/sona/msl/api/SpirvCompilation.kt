package gg.sona.msl.api

import gg.sona.msl.source.Diagnostic
import gg.sona.msl.source.Severity

class SpirvCompilation(val shaders: List<SpirvShader>, val diagnostics: List<Diagnostic>) {
    val succeeded: Boolean
        get() = diagnostics.none { it.severity == Severity.Error }

    fun shader(entryPoint: String): SpirvShader = shaders.first { it.reflection.entryPoint == entryPoint }
}
