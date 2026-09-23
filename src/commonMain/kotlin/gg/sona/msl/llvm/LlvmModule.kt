package gg.sona.msl.llvm

class LlvmModule(val triple: String, val dataLayout: String) {
    val globals = ArrayList<LlvmGlobalVariable>()
    val functions = ArrayList<LlvmFunction>()
    val namedMetadata = LinkedHashMap<String, List<MdNode>>()
    private val declarations = HashMap<String, LlvmFunction>()

    fun declare(name: String, type: LlvmFunctionType, attributes: Set<LlvmAttribute>): LlvmFunction =
        declarations.getOrPut(name) { LlvmFunction(name, type, attributes).also { functions.add(it) } }
}
