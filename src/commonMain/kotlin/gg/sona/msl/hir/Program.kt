package gg.sona.msl.hir

import gg.sona.msl.types.StructType

class Program(
    val functions: List<Function>,
    val globals: List<GlobalVariable>,
    val structs: List<StructType>,
) {
    val entryPoints: List<Function>
        get() = functions.filter { it.isEntryPoint }
}
