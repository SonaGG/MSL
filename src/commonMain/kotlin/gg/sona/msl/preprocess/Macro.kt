package gg.sona.msl.preprocess

import gg.sona.msl.lex.Token

class Macro(
    val name: String,
    val body: List<Token>,
    val parameters: List<String>? = null,
    val variadic: Boolean = false,
    val dynamic: ((Token) -> List<Token>)? = null,
) {
    val isFunctionLike: Boolean
        get() = parameters != null

    fun sameDefinitionAs(other: Macro): Boolean =
        parameters == other.parameters &&
            variadic == other.variadic &&
            body.size == other.body.size &&
            body.indices.all { body[it].kind == other.body[it].kind && body[it].text == other.body[it].text }
}
