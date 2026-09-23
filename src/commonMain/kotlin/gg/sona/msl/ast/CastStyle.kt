package gg.sona.msl.ast

enum class CastStyle(val keyword: String) {
    CStyle("(cast)"),
    Static("static_cast"),
    Reinterpret("reinterpret_cast"),
    Const("const_cast"),
    AsType("as_type"),
}
