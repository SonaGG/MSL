package gg.sona.msl.ast

enum class BinaryOperator(val spelling: String) {
    Add("+"),
    Subtract("-"),
    Multiply("*"),
    Divide("/"),
    Remainder("%"),
    ShiftLeft("<<"),
    ShiftRight(">>"),
    Less("<"),
    Greater(">"),
    LessEqual("<="),
    GreaterEqual(">="),
    Equal("=="),
    NotEqual("!="),
    BitwiseAnd("&"),
    BitwiseXor("^"),
    BitwiseOr("|"),
    LogicalAnd("&&"),
    LogicalOr("||"),
    Comma(","),
    ;

    val isComparison: Boolean
        get() = this == Less || this == Greater || this == LessEqual || this == GreaterEqual || this == Equal || this == NotEqual

    val isLogical: Boolean
        get() = this == LogicalAnd || this == LogicalOr

    val isShift: Boolean
        get() = this == ShiftLeft || this == ShiftRight

    val isBitwise: Boolean
        get() = this == BitwiseAnd || this == BitwiseXor || this == BitwiseOr
}
