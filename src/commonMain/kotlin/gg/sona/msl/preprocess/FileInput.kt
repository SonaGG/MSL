package gg.sona.msl.preprocess

import gg.sona.msl.lex.Token
import gg.sona.msl.source.SourceFile

class FileInput(val file: SourceFile, val tokens: List<Token>) {
    var index = 0
    val conditionals = ArrayList<ConditionalFrame>()

    val active: Boolean
        get() = conditionals.lastOrNull()?.active ?: true
}
