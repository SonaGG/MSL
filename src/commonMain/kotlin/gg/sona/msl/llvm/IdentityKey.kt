package gg.sona.msl.llvm

class IdentityKey(val node: MdNode) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.node === node

    override fun hashCode(): Int = node.hashCode()
}
