package gg.sona.msl.ast

enum class UnaryOperator(val spelling: String) {
    Plus("+"),
    Minus("-"),
    LogicalNot("!"),
    BitwiseNot("~"),
    Dereference("*"),
    AddressOf("&"),
    PreIncrement("++"),
    PreDecrement("--"),
    PostIncrement("++"),
    PostDecrement("--"),
}
