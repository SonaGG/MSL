package gg.sona.msl.llvm

enum class LlvmLinkage(val code: Int) {
    External(0),
    Internal(3),
}
