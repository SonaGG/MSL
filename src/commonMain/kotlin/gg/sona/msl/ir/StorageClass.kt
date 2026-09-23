package gg.sona.msl.ir

enum class StorageClass {
    Function,
    Private,
    Workgroup,
    Uniform,
    StorageBuffer,
    Input,
    Output,
    UniformConstant,
    PushConstant,
    ;

    val isExplicitlyLaidOut: Boolean
        get() = this == Uniform || this == StorageBuffer || this == PushConstant
}
