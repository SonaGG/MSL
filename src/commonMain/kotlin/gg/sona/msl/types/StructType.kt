package gg.sona.msl.types

import gg.sona.msl.source.SourceLocation

class StructType(
    val name: String,
    val location: SourceLocation,
    val isUnion: Boolean = false,
) : Type() {
    private var fieldList: List<StructField> = emptyList()
    private var layout: StructLayout? = null
    var isComplete = false
        private set
    var explicitAlignment = 0

    val fields: List<StructField>
        get() = fieldList

    fun complete(fields: List<StructField>) {
        fieldList = fields
        isComplete = true
        layout = null
    }

    fun field(name: String): StructField? = fieldList.firstOrNull { it.name == name }

    fun layout(): StructLayout = layout ?: StructLayout.compute(this).also { layout = it }

    override fun toString(): String = name
}
