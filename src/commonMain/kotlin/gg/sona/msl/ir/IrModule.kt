package gg.sona.msl.ir

class IrModule {
    val functions = ArrayList<IrFunction>()
    val globals = ArrayList<GlobalVariable>()
    val entryPoints = ArrayList<EntryPoint>()
    val specConstants = ArrayList<SpecConstant>()
    val structs = ArrayList<IrStruct>()

    fun resources(): List<GlobalVariable> = globals.filter { it.resource != null }
}
