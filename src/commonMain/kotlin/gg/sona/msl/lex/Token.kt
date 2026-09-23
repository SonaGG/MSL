package gg.sona.msl.lex

import gg.sona.msl.source.SourceLocation

class Token(
    val kind: TokenKind,
    val text: String,
    val location: SourceLocation,
    val flags: TokenFlags = TokenFlags.None,
    val hideSet: Set<String> = emptySet(),
) {
    val isIdentifier: Boolean
        get() = kind == TokenKind.Identifier

    fun isIdentifier(name: String): Boolean = kind == TokenKind.Identifier && text == name

    fun copy(
        flags: TokenFlags = this.flags,
        hideSet: Set<String> = this.hideSet,
        location: SourceLocation = this.location,
    ): Token = Token(kind, text, location, flags, hideSet)

    override fun toString(): String = if (kind.isPunctuator) kind.spelling else text
}
