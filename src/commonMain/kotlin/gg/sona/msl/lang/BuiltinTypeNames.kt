package gg.sona.msl.lang

object BuiltinTypeNames {
    val scalarNames: Set<String> = setOf(
        "void", "bool", "char", "uchar", "short", "ushort", "int", "uint", "long", "ulong", "half", "float",
        "double", "bfloat", "size_t", "ptrdiff_t", "int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t",
        "uint32_t", "int64_t", "uint64_t", "signed", "unsigned", "auto",
    )

    val vectorElementNames: List<String> = listOf(
        "bool", "char", "uchar", "short", "ushort", "int", "uint", "long", "ulong", "half", "float", "bfloat",
    )

    val textureTemplateNames: Set<String> = setOf(
        "texture1d", "texture1d_array", "texture2d", "texture2d_array", "texture3d", "texturecube",
        "texturecube_array", "texture2d_ms", "texture2d_ms_array", "texture_buffer", "depth2d", "depth2d_array",
        "depthcube", "depthcube_array", "depth2d_ms", "depth2d_ms_array",
    )

    val templateNames: Set<String> = textureTemplateNames + setOf(
        "vec", "matrix", "packed_vec", "array", "atomic", "array_ref",
    )

    val otherNames: Set<String> = setOf(
        "sampler", "atomic_int", "atomic_uint", "atomic_bool", "atomic_float", "atomic_long", "atomic_ulong",
        "bias", "level", "gradient2d", "gradient3d", "gradientcube", "min_lod_clamp",
    )

    private val all: Set<String> = buildSet {
        addAll(scalarNames)
        addAll(templateNames)
        addAll(otherNames)
        for (element in vectorElementNames) {
            for (n in 2..4) {
                add("$element$n")
                if (element != "bool") add("packed_$element$n")
            }
        }
        for (element in listOf("half", "float")) {
            for (columns in 2..4) {
                for (rows in 2..4) add("$element${columns}x$rows")
            }
        }
    }

    fun isTypeName(name: String): Boolean = name in all

    fun isTemplate(name: String): Boolean = name in templateNames
}
