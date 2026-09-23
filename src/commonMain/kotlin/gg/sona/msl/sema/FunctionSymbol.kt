package gg.sona.msl.sema

class FunctionSymbol(val name: String) : Symbol() {
    val overloads = ArrayList<FunctionOverload>()
}
