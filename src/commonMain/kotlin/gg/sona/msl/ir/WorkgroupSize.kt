package gg.sona.msl.ir

data class WorkgroupSize(val x: Int, val y: Int, val z: Int, val specializable: Boolean = false) {
    val total: Int
        get() = x * y * z
}
