package gg.sona.msl.lang

enum class AddressSpace(val spelling: String) {
    Unspecified(""),
    Device("device"),
    Constant("constant"),
    Thread("thread"),
    Threadgroup("threadgroup"),
    ThreadgroupImageblock("threadgroup_imageblock"),
    RayData("ray_data"),
    ObjectData("object_data"),
    ;

    companion object {
        fun fromKeyword(keyword: String): AddressSpace? = entries.firstOrNull { it != Unspecified && it.spelling == keyword }
    }
}
