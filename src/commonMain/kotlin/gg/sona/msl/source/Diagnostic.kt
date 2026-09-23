package gg.sona.msl.source

data class Diagnostic(
    val severity: Severity,
    val message: String,
    val location: SourceLocation,
    val position: String,
) {
    override fun toString(): String = "$position: ${severity.name.lowercase()}: $message"
}
