package gg.sona.msl.ir

class IrPointer(val pointee: IrType, val storage: StorageClass) : IrType() {
    override fun equals(other: Any?): Boolean = other is IrPointer && other.pointee == pointee && other.storage == storage

    override fun hashCode(): Int = pointee.hashCode() * 31 + storage.hashCode()

    override fun toString(): String = "ptr<${storage.name.lowercase()}, $pointee>"
}
