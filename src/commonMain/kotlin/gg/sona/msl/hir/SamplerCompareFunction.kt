package gg.sona.msl.hir

enum class SamplerCompareFunction(val spelling: String) {
    Never("never"),
    Less("less"),
    LessEqual("less_equal"),
    Greater("greater"),
    GreaterEqual("greater_equal"),
    Equal("equal"),
    NotEqual("not_equal"),
    Always("always"),
}
