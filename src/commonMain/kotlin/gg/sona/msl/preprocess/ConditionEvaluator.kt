package gg.sona.msl.preprocess

import gg.sona.msl.lex.Token
import gg.sona.msl.lex.TokenKind
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation

class ConditionEvaluator(
    private val tokens: List<Token>,
    private val diagnostics: Diagnostics,
    private val location: SourceLocation,
) {
    private var position = 0
    private var failed = false

    fun evaluate(): Long {
        if (tokens.isEmpty()) {
            diagnostics.error(location, "#if with no expression")
            return 0
        }
        val value = conditional()
        if (!failed && position < tokens.size) {
            diagnostics.error(tokens[position].location, "unexpected token '${tokens[position]}' in preprocessor expression")
        }
        return value
    }

    private fun peek(): TokenKind = tokens.getOrNull(position)?.kind ?: TokenKind.Eof

    private fun accept(kind: TokenKind): Boolean {
        if (peek() != kind) return false
        position++
        return true
    }

    private fun conditional(): Long {
        val condition = binary(0)
        if (!accept(TokenKind.Question)) return condition
        val whenTrue = conditional()
        if (!accept(TokenKind.Colon)) fail("expected ':' in conditional expression")
        val whenFalse = conditional()
        return if (condition != 0L) whenTrue else whenFalse
    }

    private fun binary(minPrecedence: Int): Long {
        var left = unary()
        while (true) {
            val kind = peek()
            val precedence = precedenceOf(kind)
            if (precedence < 0 || precedence < minPrecedence) return left
            position++
            val right = binary(precedence + 1)
            left = apply(kind, left, right)
        }
    }

    private fun apply(kind: TokenKind, left: Long, right: Long): Long = when (kind) {
        TokenKind.PipePipe -> bool(left != 0L || right != 0L)
        TokenKind.AmpAmp -> bool(left != 0L && right != 0L)
        TokenKind.Pipe -> left or right
        TokenKind.Caret -> left xor right
        TokenKind.Amp -> left and right
        TokenKind.EqualEqual -> bool(left == right)
        TokenKind.BangEqual -> bool(left != right)
        TokenKind.Less -> bool(left < right)
        TokenKind.Greater -> bool(left > right)
        TokenKind.LessEqual -> bool(left <= right)
        TokenKind.GreaterEqual -> bool(left >= right)
        TokenKind.LessLess -> left shl right.toInt()
        TokenKind.GreaterGreater -> left shr right.toInt()
        TokenKind.Plus -> left + right
        TokenKind.Minus -> left - right
        TokenKind.Star -> left * right
        TokenKind.Slash -> if (right == 0L) fail("division by zero in preprocessor expression") else left / right
        TokenKind.Percent -> if (right == 0L) fail("division by zero in preprocessor expression") else left % right
        else -> fail("unsupported operator '$kind'")
    }

    private fun unary(): Long {
        val token = tokens.getOrNull(position) ?: return fail("unexpected end of preprocessor expression")
        position++
        return when (token.kind) {
            TokenKind.Plus -> unary()
            TokenKind.Minus -> -unary()
            TokenKind.Bang -> bool(unary() == 0L)
            TokenKind.Tilde -> unary().inv()
            TokenKind.LParen -> {
                val value = conditional()
                if (!accept(TokenKind.RParen)) fail("expected ')'")
                value
            }

            TokenKind.Number -> parseInteger(token)
            TokenKind.CharLiteral -> parseChar(token)
            TokenKind.Identifier -> if (token.text == "true") 1 else 0
            else -> fail("unexpected token '$token' in preprocessor expression")
        }
    }

    private fun parseInteger(token: Token): Long {
        var text = token.text.replace("'", "").lowercase()
        while (text.isNotEmpty() && (text.last() == 'u' || text.last() == 'l')) text = text.dropLast(1)
        val value = when {
            text.startsWith("0x") -> text.substring(2).toULongOrNull(16)?.toLong()
            text.startsWith("0b") -> text.substring(2).toULongOrNull(2)?.toLong()
            text.length > 1 && text.startsWith("0") -> text.substring(1).toULongOrNull(8)?.toLong()
            else -> text.toULongOrNull()?.toLong()
        }
        return value ?: fail("invalid integer '${token.text}' in preprocessor expression")
    }

    private fun parseChar(token: Token): Long {
        val body = token.text.removePrefix("'").removeSuffix("'")
        if (body.isEmpty()) return 0
        if (body[0] != '\\') return body[0].code.toLong()
        return when (body.getOrNull(1)) {
            'n' -> 10
            't' -> 9
            'r' -> 13
            '0' -> 0
            '\\' -> 92
            '\'' -> 39
            else -> body.getOrNull(1)?.code?.toLong() ?: 0
        }
    }

    private fun precedenceOf(kind: TokenKind): Int = when (kind) {
        TokenKind.PipePipe -> 1
        TokenKind.AmpAmp -> 2
        TokenKind.Pipe -> 3
        TokenKind.Caret -> 4
        TokenKind.Amp -> 5
        TokenKind.EqualEqual, TokenKind.BangEqual -> 6
        TokenKind.Less, TokenKind.Greater, TokenKind.LessEqual, TokenKind.GreaterEqual -> 7
        TokenKind.LessLess, TokenKind.GreaterGreater -> 8
        TokenKind.Plus, TokenKind.Minus -> 9
        TokenKind.Star, TokenKind.Slash, TokenKind.Percent -> 10
        else -> -1
    }

    private fun bool(value: Boolean): Long = if (value) 1 else 0

    private fun fail(message: String): Long {
        if (!failed) diagnostics.error(tokens.getOrNull(position)?.location ?: location, message)
        failed = true
        position = tokens.size
        return 0
    }
}
