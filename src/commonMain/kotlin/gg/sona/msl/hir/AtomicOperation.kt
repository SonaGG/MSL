package gg.sona.msl.hir

enum class AtomicOperation {
    Load,
    Store,
    Exchange,
    CompareExchange,
    Add,
    Sub,
    And,
    Or,
    Xor,
    Min,
    Max,
}
