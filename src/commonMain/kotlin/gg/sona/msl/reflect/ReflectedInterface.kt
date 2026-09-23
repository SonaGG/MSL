package gg.sona.msl.reflect

import gg.sona.msl.ir.BuiltinVariable

data class ReflectedInterface(
    val name: String,
    val location: Int,
    val builtin: BuiltinVariable?,
    val type: String,
)
