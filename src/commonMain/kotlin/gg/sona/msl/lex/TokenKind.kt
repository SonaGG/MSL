package gg.sona.msl.lex

import kotlin.jvm.JvmInline

@JvmInline
value class TokenKind(val id: Int) {
    val spelling: String
        get() = SPELLINGS[id]

    val isPunctuator: Boolean
        get() = id >= LParen.id

    override fun toString(): String = SPELLINGS[id]

    companion object {
        val Eof = TokenKind(0)
        val Identifier = TokenKind(1)
        val Number = TokenKind(2)
        val CharLiteral = TokenKind(3)
        val StringLiteral = TokenKind(4)
        val HeaderName = TokenKind(5)
        val Unknown = TokenKind(6)
        val LParen = TokenKind(7)
        val RParen = TokenKind(8)
        val LBracket = TokenKind(9)
        val RBracket = TokenKind(10)
        val LBrace = TokenKind(11)
        val RBrace = TokenKind(12)
        val Semicolon = TokenKind(13)
        val Comma = TokenKind(14)
        val Dot = TokenKind(15)
        val Arrow = TokenKind(16)
        val Ellipsis = TokenKind(17)
        val Plus = TokenKind(18)
        val Minus = TokenKind(19)
        val Star = TokenKind(20)
        val Slash = TokenKind(21)
        val Percent = TokenKind(22)
        val Amp = TokenKind(23)
        val Pipe = TokenKind(24)
        val Caret = TokenKind(25)
        val Tilde = TokenKind(26)
        val Bang = TokenKind(27)
        val Question = TokenKind(28)
        val Colon = TokenKind(29)
        val ColonColon = TokenKind(30)
        val Less = TokenKind(31)
        val Greater = TokenKind(32)
        val LessEqual = TokenKind(33)
        val GreaterEqual = TokenKind(34)
        val EqualEqual = TokenKind(35)
        val BangEqual = TokenKind(36)
        val AmpAmp = TokenKind(37)
        val PipePipe = TokenKind(38)
        val LessLess = TokenKind(39)
        val GreaterGreater = TokenKind(40)
        val PlusPlus = TokenKind(41)
        val MinusMinus = TokenKind(42)
        val Equal = TokenKind(43)
        val PlusEqual = TokenKind(44)
        val MinusEqual = TokenKind(45)
        val StarEqual = TokenKind(46)
        val SlashEqual = TokenKind(47)
        val PercentEqual = TokenKind(48)
        val AmpEqual = TokenKind(49)
        val PipeEqual = TokenKind(50)
        val CaretEqual = TokenKind(51)
        val LessLessEqual = TokenKind(52)
        val GreaterGreaterEqual = TokenKind(53)
        val Hash = TokenKind(54)
        val HashHash = TokenKind(55)

        private val SPELLINGS = arrayOf(
            "<eof>",
            "<identifier>",
            "<number>",
            "<char>",
            "<string>",
            "<header>",
            "<unknown>",
            "(",
            ")",
            "[",
            "]",
            "{",
            "}",
            ";",
            ",",
            ".",
            "->",
            "...",
            "+",
            "-",
            "*",
            "/",
            "%",
            "&",
            "|",
            "^",
            "~",
            "!",
            "?",
            ":",
            "::",
            "<",
            ">",
            "<=",
            ">=",
            "==",
            "!=",
            "&&",
            "||",
            "<<",
            ">>",
            "++",
            "--",
            "=",
            "+=",
            "-=",
            "*=",
            "/=",
            "%=",
            "&=",
            "|=",
            "^=",
            "<<=",
            ">>=",
            "#",
            "##",
        )

        val PUNCTUATORS: List<TokenKind> = (LParen.id..HashHash.id)
            .map { TokenKind(it) }
            .sortedByDescending { it.spelling.length }
    }
}
