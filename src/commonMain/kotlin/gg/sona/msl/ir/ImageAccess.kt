package gg.sona.msl.ir

enum class ImageAccess {
    Sample,
    Read,
    Write,
    ReadWrite,
    ;

    val isStorage: Boolean
        get() = this == Write || this == ReadWrite
}
