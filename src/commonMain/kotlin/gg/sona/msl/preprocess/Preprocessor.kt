package gg.sona.msl.preprocess

import gg.sona.msl.lex.Lexer
import gg.sona.msl.lex.Token
import gg.sona.msl.lex.TokenFlags
import gg.sona.msl.lex.TokenKind
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceFile
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.source.SourceManager

class Preprocessor(
    private val sources: SourceManager,
    private val diagnostics: Diagnostics,
    private val includeResolver: IncludeResolver = IncludeResolver.None,
    predefinedMacros: Map<String, String> = emptyMap(),
) {
    private val macros = HashMap<String, Macro>()
    private val onceFiles = HashSet<String>()
    private val stack = ArrayList<FileInput>()
    private var counter = 0
    private var lastEof = Token(TokenKind.Eof, "", SourceLocation.NONE)

    init {
        defineDynamic("__LINE__") { token ->
            val file = sources[token.location.fileId]
            listOf(number(file?.lineOf(token.location.offset)?.toString() ?: "0", token))
        }
        defineDynamic("__FILE__") { token ->
            val name = sources[token.location.fileId]?.name ?: "<unknown>"
            listOf(Token(TokenKind.StringLiteral, "\"" + name.replace("\\", "\\\\") + "\"", token.location))
        }
        defineDynamic("__COUNTER__") { token -> listOf(number((counter++).toString(), token)) }
        for ((name, value) in predefinedMacros) define(name, value)
    }

    val definedMacros: Set<String>
        get() = macros.keys

    fun define(name: String, value: String = "1") {
        val file = sources.add("<predefined $name>", value)
        val body = Lexer(file, diagnostics).tokenize().dropLast(1)
        macros[name] = Macro(name, body)
    }

    fun process(file: SourceFile): List<Token> {
        stack.add(FileInput(file, Lexer(file, diagnostics).tokenize()))
        val expander = MacroExpander(macros, diagnostics, ::readFromFiles)
        val output = ArrayList<Token>()
        while (true) {
            val token = expander.next()
            if (token.kind == TokenKind.Eof) break
            output.add(token)
        }
        output.add(lastEof)
        return output
    }

    private fun defineDynamic(name: String, producer: (Token) -> List<Token>) {
        macros[name] = Macro(name, emptyList(), dynamic = producer)
    }

    private fun number(text: String, at: Token) = Token(TokenKind.Number, text, at.location)

    private fun readFromFiles(): Token {
        while (true) {
            val input = stack.lastOrNull() ?: return lastEof
            val token = input.tokens[input.index]
            if (token.kind == TokenKind.Eof) {
                for (frame in input.conditionals) diagnostics.error(frame.location, "unterminated conditional directive")
                stack.removeAt(stack.size - 1)
                if (stack.isEmpty()) {
                    lastEof = token
                    return token
                }
                continue
            }
            input.index++
            if (token.kind == TokenKind.Hash && token.flags.startOfLine) {
                directive(input, token)
                continue
            }
            if (!input.active) continue
            return token
        }
    }

    private fun readLine(input: FileInput): List<Token> {
        val line = ArrayList<Token>()
        while (true) {
            val token = input.tokens[input.index]
            if (token.kind == TokenKind.Eof || token.flags.startOfLine) return line
            line.add(token)
            input.index++
        }
    }

    private fun directive(input: FileInput, hash: Token) {
        val line = readLine(input)
        val name = line.firstOrNull() ?: return
        val rest = line.subList(1, line.size)
        if (name.kind != TokenKind.Identifier) {
            if (input.active) diagnostics.error(name.location, "invalid preprocessing directive")
            return
        }
        when (name.text) {
            "if" -> pushConditional(input, hash) { evaluate(rest, name.location) }
            "ifdef" -> pushConditional(input, hash) { isDefined(rest, name) }
            "ifndef" -> pushConditional(input, hash) { !isDefined(rest, name) }
            "elif" -> elseBranch(input, name) { evaluate(rest, name.location) }
            "elifdef" -> elseBranch(input, name) { isDefined(rest, name) }
            "elifndef" -> elseBranch(input, name) { !isDefined(rest, name) }
            "else" -> {
                val frame = input.conditionals.lastOrNull()
                    ?: return diagnostics.error(name.location, "#else without #if")
                if (frame.seenElse) diagnostics.error(name.location, "#else after #else")
                frame.seenElse = true
                frame.active = frame.parentActive && !frame.anyBranchTaken
                frame.anyBranchTaken = true
            }

            "endif" -> {
                if (input.conditionals.isEmpty()) {
                    diagnostics.error(name.location, "#endif without #if")
                } else {
                    input.conditionals.removeAt(input.conditionals.size - 1)
                }
            }

            else -> if (input.active) activeDirective(input, name, rest)
        }
    }

    private fun activeDirective(input: FileInput, name: Token, rest: List<Token>) {
        when (name.text) {
            "define" -> defineDirective(rest, name)
            "undef" -> {
                val target = rest.firstOrNull()
                if (target == null || target.kind != TokenKind.Identifier) {
                    diagnostics.error(name.location, "macro name missing")
                } else {
                    macros.remove(target.text)
                }
            }

            "include", "include_next", "import" -> include(input, name, rest)
            "pragma" -> if (rest.firstOrNull()?.text == "once") onceFiles.add(input.file.name)
            "error" -> diagnostics.error(name.location, "#error " + rest.joinToString(" "))
            "warning" -> diagnostics.warning(name.location, "#warning " + rest.joinToString(" "))
            "line" -> Unit
            else -> diagnostics.error(name.location, "unknown preprocessing directive '#${name.text}'")
        }
    }

    private inline fun pushConditional(input: FileInput, hash: Token, condition: () -> Boolean) {
        val parentActive = input.active
        val value = parentActive && condition()
        input.conditionals.add(ConditionalFrame(parentActive, value, value, false, hash.location))
    }

    private inline fun elseBranch(input: FileInput, name: Token, condition: () -> Boolean) {
        val frame = input.conditionals.lastOrNull()
            ?: return diagnostics.error(name.location, "#${name.text} without #if")
        if (frame.seenElse) diagnostics.error(name.location, "#${name.text} after #else")
        if (!frame.parentActive || frame.anyBranchTaken) {
            frame.active = false
            return
        }
        val value = condition()
        frame.active = value
        frame.anyBranchTaken = value
    }

    private fun isDefined(rest: List<Token>, at: Token): Boolean {
        val target = rest.firstOrNull()
        if (target == null || target.kind != TokenKind.Identifier) {
            diagnostics.error(at.location, "macro name missing")
            return false
        }
        return target.text in macros
    }

    private fun evaluate(tokens: List<Token>, location: SourceLocation): Boolean {
        val resolved = ArrayList<Token>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.isIdentifier("defined")) {
                val parenthesized = tokens.getOrNull(i + 1)?.kind == TokenKind.LParen
                val nameToken = tokens.getOrNull(if (parenthesized) i + 2 else i + 1)
                if (nameToken == null || nameToken.kind != TokenKind.Identifier) {
                    diagnostics.error(token.location, "'defined' requires an identifier")
                    return false
                }
                resolved.add(number(if (nameToken.text in macros) "1" else "0", token))
                i += if (parenthesized) 4 else 2
                continue
            }
            if (token.kind == TokenKind.Identifier && token.text in HAS_QUERIES &&
                tokens.getOrNull(i + 1)?.kind == TokenKind.LParen
            ) {
                var end = i + 2
                var depth = 1
                while (end < tokens.size) {
                    if (tokens[end].kind == TokenKind.LParen) depth++
                    if (tokens[end].kind == TokenKind.RParen && --depth == 0) break
                    end++
                }
                val arguments = tokens.subList(i + 2, end.coerceAtMost(tokens.size))
                resolved.add(number(if (hasQuery(token.text, arguments)) "1" else "0", token))
                i = end + 1
                continue
            }
            resolved.add(token)
            i++
        }
        var position = 0
        val eof = Token(TokenKind.Eof, "", location)
        val expanded = MacroExpander(macros, diagnostics) { resolved.getOrNull(position++) ?: eof }.drain()
        return ConditionEvaluator(expanded, diagnostics, location).evaluate() != 0L
    }

    private fun hasQuery(query: String, arguments: List<Token>): Boolean = when (query) {
        "__has_include", "__has_include_next" -> {
            val spec = headerSpec(arguments)
            spec != null && (isBuiltinHeader(spec.first) ||
                includeResolver.resolve(spec.first, spec.second, stack.last().file.name) != null)
        }

        "__has_attribute", "__has_cpp_attribute" -> arguments.lastOrNull()?.text in KNOWN_ATTRIBUTES
        "__has_builtin", "__has_feature", "__has_extension" -> false
        else -> false
    }

    private fun defineDirective(rest: List<Token>, at: Token) {
        val nameToken = rest.firstOrNull()
        if (nameToken == null || nameToken.kind != TokenKind.Identifier) {
            diagnostics.error(at.location, "macro name missing")
            return
        }
        val name = nameToken.text
        val next = rest.getOrNull(1)
        val macro = if (next != null && next.kind == TokenKind.LParen && !next.flags.leadingSpace) {
            val parameters = ArrayList<String>()
            var variadic = false
            var i = 2
            while (i < rest.size && rest[i].kind != TokenKind.RParen) {
                val token = rest[i]
                when {
                    token.kind == TokenKind.Ellipsis -> {
                        variadic = true
                        parameters.add("__VA_ARGS__")
                    }

                    token.kind == TokenKind.Identifier -> parameters.add(token.text)
                    token.kind == TokenKind.Comma -> Unit
                    else -> {
                        diagnostics.error(token.location, "invalid macro parameter list")
                        return
                    }
                }
                i++
            }
            if (i >= rest.size) {
                diagnostics.error(at.location, "missing ')' in macro parameter list")
                return
            }
            Macro(name, trimFirst(rest.subList(i + 1, rest.size)), parameters, variadic)
        } else {
            Macro(name, trimFirst(rest.subList(1, rest.size)))
        }
        val existing = macros[name]
        if (existing != null && existing.dynamic == null && !existing.sameDefinitionAs(macro)) {
            diagnostics.warning(nameToken.location, "'$name' macro redefined")
        }
        macros[name] = macro
    }

    private fun trimFirst(tokens: List<Token>): List<Token> {
        if (tokens.isEmpty()) return tokens
        val first = tokens[0]
        return listOf(first.copy(flags = first.flags - TokenFlags.LeadingSpace - TokenFlags.StartOfLine)) +
            tokens.subList(1, tokens.size)
    }

    private fun headerSpec(tokens: List<Token>): Pair<String, Boolean>? {
        val first = tokens.firstOrNull() ?: return null
        if (first.kind == TokenKind.StringLiteral) return first.text.removeSurrounding("\"") to false
        if (first.kind != TokenKind.Less) return null
        val builder = StringBuilder()
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            if (token.kind == TokenKind.Greater) return builder.toString() to true
            if (token.flags.leadingSpace && builder.isNotEmpty()) builder.append(' ')
            builder.append(token.text)
        }
        return null
    }

    private fun include(input: FileInput, at: Token, rest: List<Token>) {
        var spec = headerSpec(rest)
        if (spec == null) {
            var position = 0
            val eof = Token(TokenKind.Eof, "", at.location)
            spec = headerSpec(MacroExpander(macros, diagnostics) { rest.getOrNull(position++) ?: eof }.drain())
        }
        if (spec == null) {
            diagnostics.error(at.location, "expected \"FILENAME\" or <FILENAME>")
            return
        }
        val (path, system) = spec
        if (isBuiltinHeader(path)) return
        val resolved = includeResolver.resolve(path, system, input.file.name)
        if (resolved == null) {
            diagnostics.error(at.location, "'$path' file not found")
            return
        }
        if (resolved.name in onceFiles) return
        if (stack.size >= MAX_INCLUDE_DEPTH) {
            diagnostics.error(at.location, "#include nested too deeply")
            return
        }
        val file = sources.add(resolved.name, resolved.text)
        stack.add(FileInput(file, Lexer(file, diagnostics).tokenize()))
    }

    private fun isBuiltinHeader(path: String): Boolean =
        path.startsWith("metal_") || path in BUILTIN_HEADERS

    private companion object {
        const val MAX_INCLUDE_DEPTH = 200

        val BUILTIN_HEADERS = setOf("simd/simd.h", "simd/vector_types.h", "simd/matrix.h", "metal/metal.h")

        val HAS_QUERIES = setOf(
            "__has_include",
            "__has_include_next",
            "__has_attribute",
            "__has_cpp_attribute",
            "__has_builtin",
            "__has_feature",
            "__has_extension",
        )

        val KNOWN_ATTRIBUTES = setOf(
            "buffer",
            "texture",
            "sampler",
            "stage_in",
            "position",
            "attribute",
            "function_constant",
            "early_fragment_tests",
        )
    }
}
