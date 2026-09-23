package gg.sona.msl.lang

enum class ShaderStage(val keyword: String) {
    Vertex("vertex"),
    Fragment("fragment"),
    Kernel("kernel"),
    ;

    companion object {
        fun fromKeyword(keyword: String): ShaderStage? = entries.firstOrNull { it.keyword == keyword }
    }
}
