package gg.sona.msl.types

enum class TextureAccess(val spelling: String) {
    Sample("sample"),
    Read("read"),
    Write("write"),
    ReadWrite("read_write"),
    ;

    val canRead: Boolean
        get() = this != Write

    val canWrite: Boolean
        get() = this == Write || this == ReadWrite

    val isStorage: Boolean
        get() = this != Sample
}
