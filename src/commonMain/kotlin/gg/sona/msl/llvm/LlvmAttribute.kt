package gg.sona.msl.llvm

enum class LlvmAttribute(val code: Int) {
    NoDuplicate(12),
    NoUnwind(18),
    ReadNone(20),
    ReadOnly(21),
}
