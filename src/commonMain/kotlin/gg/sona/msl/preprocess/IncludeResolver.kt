package gg.sona.msl.preprocess

fun interface IncludeResolver {
    fun resolve(path: String, system: Boolean, includerName: String): IncludedSource?

    companion object {
        val None = IncludeResolver { _, _, _ -> null }
    }
}
