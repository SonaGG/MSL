package gg.sona.msl.preprocess

import gg.sona.msl.lex.Lexer
import gg.sona.msl.lex.Token
import gg.sona.msl.lex.TokenFlags
import gg.sona.msl.lex.TokenKind
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceFile
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.source.SourceManager

class MacroExpander(
    private val macros: Map<String, Macro>,
    private val diagnostics: Diagnostics,
    private val source: () -> Token,
) {
    private val pending = ArrayDeque<Token>()

    fun next(): Token {
        while (true) {
            val token = read()
            if (token.kind != TokenKind.Identifier || token.text in token.hideSet) return token
            val macro = macros[token.text] ?: return token
            if (!expand(token, macro)) return token
        }
    }

    fun drain(): List<Token> {
        val result = ArrayList<Token>()
        while (true) {
            val token = next()
            if (token.kind == TokenKind.Eof) return result
            result.add(token)
        }
    }

    private fun read(): Token = pending.removeFirstOrNull() ?: source()

    private fun expand(invocation: Token, macro: Macro): Boolean {
        val dynamic = macro.dynamic
        if (dynamic != null) {
            pushFront(dynamic(invocation).map { it.copy(location = invocation.location) }, invocation)
            return true
        }
        if (!macro.isFunctionLike) {
            val hideSet = invocation.hideSet + macro.name
            val body = macro.body.map { it.copy(hideSet = it.hideSet + hideSet, location = invocation.location) }
            pushFront(body, invocation)
            return true
        }
        val open = read()
        if (open.kind != TokenKind.LParen) {
            pending.addFirst(open)
            return false
        }
        val parameterCount = macro.parameters!!.size
        val arguments = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        var depth = 0
        val close: Token
        while (true) {
            val token = read()
            if (token.kind == TokenKind.Eof) {
                diagnostics.error(invocation.location, "unterminated invocation of macro '${macro.name}'")
                pending.addFirst(token)
                return false
            }
            if (depth == 0 && token.kind == TokenKind.RParen) {
                close = token
                break
            }
            val collectsRest = macro.variadic && arguments.size >= parameterCount - 1
            if (depth == 0 && token.kind == TokenKind.Comma && !collectsRest) {
                arguments.add(current)
                current = ArrayList()
                continue
            }
            if (token.kind == TokenKind.LParen) depth++
            if (token.kind == TokenKind.RParen) depth--
            current.add(token)
        }
        if (arguments.isNotEmpty() || current.isNotEmpty() || parameterCount > 0) arguments.add(current)
        while (arguments.size < parameterCount && macro.variadic) arguments.add(emptyList())
        if (arguments.size != parameterCount) {
            diagnostics.error(
                invocation.location,
                "macro '${macro.name}' expects $parameterCount arguments, got ${arguments.size}",
            )
            return false
        }
        val hideSet = invocation.hideSet.intersect(close.hideSet) + macro.name
        val body = substitute(macro, arguments)
        pushFront(body.map { it.copy(hideSet = it.hideSet + hideSet, location = invocation.location) }, invocation)
        return true
    }

    private fun pushFront(tokens: List<Token>, invocation: Token) {
        val positional = TokenFlags.StartOfLine + TokenFlags.LeadingSpace
        for (i in tokens.indices.reversed()) {
            var token = tokens[i]
            if (i == 0) {
                val flags = token.flags - positional + TokenFlags(invocation.flags.bits and positional.bits)
                token = token.copy(flags = flags)
            }
            pending.addFirst(token)
        }
    }

    private fun substitute(macro: Macro, arguments: List<List<Token>>): List<Token> {
        val parameters = macro.parameters!!
        val expandedCache = arrayOfNulls<List<Token>>(arguments.size)

        fun parameterIndex(token: Token): Int {
            if (token.kind != TokenKind.Identifier) return -1
            if (macro.variadic && token.text == "__VA_ARGS__") return parameters.size - 1
            return parameters.indexOf(token.text)
        }

        fun expanded(index: Int): List<Token> = expandedCache[index] ?: run {
            val tokens = arguments[index]
            var position = 0
            val eof = Token(TokenKind.Eof, "", SourceLocation.NONE)
            val result = MacroExpander(macros, diagnostics) { tokens.getOrNull(position++) ?: eof }.drain()
            expandedCache[index] = result
            result
        }

        val variadicEmpty = macro.variadic && arguments.last().isEmpty()
        val body = macro.body
        val output = ArrayList<Token>()
        var i = 0
        while (i < body.size) {
            val token = body[i]
            if (token.kind == TokenKind.Hash && i + 1 < body.size && parameterIndex(body[i + 1]) >= 0) {
                output.add(stringize(arguments[parameterIndex(body[i + 1])], token))
                i += 2
                continue
            }
            if (token.isIdentifier("__VA_OPT__") && macro.variadic &&
                body.getOrNull(i + 1)?.kind == TokenKind.LParen
            ) {
                var depth = 0
                var end = i + 1
                while (end < body.size) {
                    if (body[end].kind == TokenKind.LParen) depth++
                    if (body[end].kind == TokenKind.RParen && --depth == 0) break
                    end++
                }
                if (!variadicEmpty) {
                    val inner = Macro(macro.name, body.subList(i + 2, end), parameters, true)
                    output.addAll(substitute(inner, arguments))
                }
                i = end + 1
                continue
            }
            if (token.kind == TokenKind.HashHash && output.isNotEmpty() && i + 1 < body.size) {
                val right = body[i + 1]
                val rightIndex = parameterIndex(right)
                val elidesComma = rightIndex == parameters.size - 1 && macro.variadic && variadicEmpty &&
                    output.last().kind == TokenKind.Comma
                if (elidesComma) {
                    output.removeAt(output.size - 1)
                    i += 2
                    continue
                }
                val rightTokens = if (rightIndex >= 0) arguments[rightIndex] else listOf(right)
                if (rightTokens.isNotEmpty()) {
                    val left = output.removeAt(output.size - 1)
                    output.add(paste(left, rightTokens.first()))
                    output.addAll(rightTokens.drop(1))
                }
                i += 2
                continue
            }
            val index = parameterIndex(token)
            if (index >= 0) {
                val nextIsPaste = body.getOrNull(i + 1)?.kind == TokenKind.HashHash
                val replacement = if (nextIsPaste) arguments[index] else expanded(index)
                replacement.forEachIndexed { n, t ->
                    output.add(if (n == 0) t.copy(flags = t.flags - TokenFlags.LeadingSpace + leading(token)) else t)
                }
                i++
                continue
            }
            output.add(token)
            i++
        }
        return output
    }

    private fun leading(token: Token): TokenFlags =
        if (token.flags.leadingSpace) TokenFlags.LeadingSpace else TokenFlags.None

    private fun stringize(tokens: List<Token>, at: Token): Token {
        val builder = StringBuilder("\"")
        tokens.forEachIndexed { index, token ->
            if (index > 0 && token.flags.leadingSpace) builder.append(' ')
            val text = if (token.kind.isPunctuator) token.kind.spelling else token.text
            if (token.kind == TokenKind.StringLiteral || token.kind == TokenKind.CharLiteral) {
                for (c in text) {
                    if (c == '"' || c == '\\') builder.append('\\')
                    builder.append(c)
                }
            } else {
                builder.append(text)
            }
        }
        builder.append('"')
        return Token(TokenKind.StringLiteral, builder.toString(), at.location, at.flags)
    }

    private fun paste(left: Token, right: Token): Token {
        val text = left.text + right.text
        val scratch = Diagnostics(SourceManager())
        val tokens = Lexer(SourceFile(0, "<paste>", text), scratch).tokenize()
        if (tokens.size != 2 || scratch.hasErrors) {
            diagnostics.error(left.location, "pasting \"${left.text}\" and \"${right.text}\" does not give a valid token")
            return left
        }
        return Token(tokens[0].kind, tokens[0].text, left.location, left.flags, left.hideSet.intersect(right.hideSet))
    }
}
