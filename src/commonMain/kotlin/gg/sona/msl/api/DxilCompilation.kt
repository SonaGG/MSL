package gg.sona.msl.api

import gg.sona.msl.source.Diagnostic
import gg.sona.msl.source.Severity

class DxilCompilation(val shaders: List<DxilShader>, val diagnostics: List<Diagnostic>) {
    val succeeded: Boolean
        get() = diagnostics.none { it.severity == Severity.Error }

    fun shader(entryPoint: String): DxilShader = shaders.first { it.reflection.entryPoint == entryPoint }
}
