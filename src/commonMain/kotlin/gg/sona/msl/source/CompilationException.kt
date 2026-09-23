package gg.sona.msl.source

class CompilationException(val diagnostics: List<Diagnostic>) :
    RuntimeException(diagnostics.joinToString("\n"))
