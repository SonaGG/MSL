package gg.sona.msl.llvm

enum class LlvmOpcode(val isTerminator: Boolean = false) {
    Ret(true),
    Br(true),
    Switch(true),
    Unreachable(true),
    BinOp,
    Cast,
    Cmp,
    Select,
    Phi,
    Call,
    ExtractValue,
    InsertValue,
    Alloca,
    Load,
    Store,
    GetElementPtr,
    AtomicRmw,
    CmpXchg,
}
