package gg.sona.msl.llvm

data class LlvmPointerType(val pointee: LlvmType, val addressSpace: Int = 0) : LlvmType() {
    override fun toString(): String = "$pointee" + (if (addressSpace != 0) " addrspace($addressSpace)" else "") + "*"
}
