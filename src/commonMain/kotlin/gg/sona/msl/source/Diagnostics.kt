package gg.sona.msl.source

class Diagnostics(val sources: SourceManager) {
    val all: List<Diagnostic>
        field = ArrayList<Diagnostic>()

    val hasErrors: Boolean
        get() = all.any { it.severity == Severity.Error }

    fun error(location: SourceLocation, message: String) = report(Severity.Error, location, message)

    fun warning(location: SourceLocation, message: String) = report(Severity.Warning, location, message)

    fun note(location: SourceLocation, message: String) = report(Severity.Note, location, message)

    fun report(severity: Severity, location: SourceLocation, message: String) {
        all.add(Diagnostic(severity, message, location, sources.describe(location)))
    }

    fun fatal(location: SourceLocation, message: String): Nothing {
        error(location, message)
        throw CompilationException(all.toList())
    }

    fun throwIfErrors() {
        if (hasErrors) throw CompilationException(all.toList())
    }
}
