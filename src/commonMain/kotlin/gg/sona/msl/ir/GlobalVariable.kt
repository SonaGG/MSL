package gg.sona.msl.ir

class GlobalVariable(
    var name: String,
    var valueType: IrType,
    val storage: StorageClass,
) : Value() {
    override val type: IrType
        get() = IrPointer(valueType, storage)
    var initializer: IrConstant? = null
    var resource: ResourceInfo? = null
    var interfaceInfo: InterfaceInfo? = null
    var lengthSpecialization: SpecConstant? = null
    var isConstant: Boolean = false

    override fun toString(): String = "@$name"
}
