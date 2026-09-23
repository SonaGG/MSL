package gg.sona.msl.hir

enum class HBinaryOperator(val spelling: String) {
    Add("+"),
    Subtract("-"),
    Multiply("*"),
    Divide("/"),
    Remainder("%"),
    ShiftLeft("<<"),
    ShiftRight(">>"),
    BitwiseAnd("&"),
    BitwiseOr("|"),
    BitwiseXor("^"),
    Equal("=="),
    NotEqual("!="),
    Less("<"),
    LessEqual("<="),
    Greater(">"),
    GreaterEqual(">="),
    ;

    val isComparison: Boolean
        get() = ordinal >= Equal.ordinal
}
