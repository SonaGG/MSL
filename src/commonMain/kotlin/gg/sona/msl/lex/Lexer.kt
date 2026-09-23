package gg.sona.msl.lex

import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceFile
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.util.IntList

class Lexer(private val file: SourceFile, private val diagnostics: Diagnostics) {
    private val text: String
    private val offsets: IntArray?
    private var position = 0
    private var pendingFlags = TokenFlags.StartOfLine

    init {
        if (file.text.contains('\\')) {
            val builder = StringBuilder(file.text.length)
            val map = IntList(file.text.length + 1)
            var i = 0
            val source = file.text
            while (i < source.length) {
                val c = source[i]
                if (c == '\\') {
                    val next = source.getOrNull(i + 1)
                    if (next == '\n') {
                        i += 2
                        continue
                    }
                    if (next == '\r' && source.getOrNull(i + 2) == '\n') {
                        i += 3
                        continue
                    }
                }
                builder.append(c)
                map.add(i)
                i++
            }
            map.add(source.length)
            text = builder.toString()
            offsets = map.toIntArray()
        } else {
            text = file.text
            offsets = null
        }
    }

    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>(text.length / 3)
        while (true) {
            val token = next()
            tokens.add(token)
            if (token.kind == TokenKind.Eof) return tokens
        }
    }

    private fun next(): Token {
        skipTrivia()
        val start = position
        if (position >= text.length) return make(TokenKind.Eof, start)
        val c = text[position]
        return when {
            c.isIdentifierStart() -> {
                position++
                while (position < text.length && text[position].isIdentifierPart()) position++
                make(TokenKind.Identifier, start)
            }

            c.isDigit() || (c == '.' && text.getOrNull(position + 1)?.isDigit() == true) -> lexNumber(start)
            c == '"' -> lexQuoted(start, '"', TokenKind.StringLiteral)
            c == '\'' -> lexQuoted(start, '\'', TokenKind.CharLiteral)
            else -> lexPunctuator(start)
        }
    }

    private fun skipTrivia() {
        while (position < text.length) {
            val c = text[position]
            when {
                c == '\n' -> {
                    pendingFlags = TokenFlags.StartOfLine
                    position++
                }

                c == ' ' || c == '\t' || c == '\r' || c == '\u000B' || c == '\u000C' -> {
                    pendingFlags += TokenFlags.LeadingSpace
                    position++
                }

                c == '/' && text.getOrNull(position + 1) == '/' -> {
                    while (position < text.length && text[position] != '\n') position++
                    pendingFlags += TokenFlags.LeadingSpace
                }

                c == '/' && text.getOrNull(position + 1) == '*' -> {
                    val start = position
                    val end = text.indexOf("*/", position + 2)
                    if (end < 0) {
                        diagnostics.error(location(start, 2), "unterminated block comment")
                        position = text.length
                    } else {
                        if (text.indexOf('\n', start) in start..<end) pendingFlags += TokenFlags.StartOfLine
                        position = end + 2
                    }
                    pendingFlags += TokenFlags.LeadingSpace
                }

                else -> return
            }
        }
    }

    private fun lexNumber(start: Int): Token {
        position++
        while (position < text.length) {
            val c = text[position]
            val previous = text[position - 1]
            when {
                (c == '+' || c == '-') && previous in EXPONENT_MARKERS -> position++
                c.isIdentifierPart() || c == '.' -> position++
                c == '\'' && text.getOrNull(position + 1)?.isLetterOrDigit() == true -> position++
                else -> break
            }
        }
        return make(TokenKind.Number, start)
    }

    private fun lexQuoted(start: Int, quote: Char, kind: TokenKind): Token {
        position++
        while (position < text.length) {
            val c = text[position]
            if (c == '\\') {
                position += 2
                continue
            }
            if (c == quote) {
                position++
                return make(kind, start)
            }
            if (c == '\n') break
            position++
        }
        diagnostics.error(location(start, position - start), "unterminated literal")
        return make(kind, start)
    }

    private fun lexPunctuator(start: Int): Token {
        for (kind in TokenKind.PUNCTUATORS) {
            if (text.startsWith(kind.spelling, position)) {
                position += kind.spelling.length
                return make(kind, start)
            }
        }
        position++
        diagnostics.error(location(start, 1), "unexpected character '${text[start]}'")
        return make(TokenKind.Unknown, start)
    }

    private fun make(kind: TokenKind, start: Int): Token {
        val token = Token(kind, text.substring(start, position), location(start, position - start), pendingFlags)
        pendingFlags = TokenFlags.None
        return token
    }

    private fun location(start: Int, length: Int): SourceLocation {
        if (offsets == null) return SourceLocation(file.id, start, length)
        val originalStart = offsets[start.coerceAtMost(offsets.size - 1)]
        val originalEnd = offsets[(start + length).coerceAtMost(offsets.size - 1)]
        return SourceLocation(file.id, originalStart, originalEnd - originalStart)
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || this in '0'..'9'

    private companion object {
        const val EXPONENT_MARKERS = "eEpP"
    }
}
