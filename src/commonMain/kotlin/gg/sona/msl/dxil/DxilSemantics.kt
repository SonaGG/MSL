package gg.sona.msl.dxil

fun interface DxilSemantics {
    fun semantic(location: Int, isVertexInput: Boolean, name: String): Pair<String, Int>

    companion object {
        val Default = DxilSemantics { location, isVertexInput, _ ->
            if (isVertexInput) "ATTRIBUTE" to location else "TEXCOORD" to location
        }
    }
}
