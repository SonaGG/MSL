package gg.sona.msl.parse

import gg.sona.msl.ast.ArrayTypeSyntax
import gg.sona.msl.ast.AssignExpr
import gg.sona.msl.ast.Attribute
import gg.sona.msl.ast.BinaryExpr
import gg.sona.msl.ast.BinaryOperator
import gg.sona.msl.ast.BlockStmt
import gg.sona.msl.ast.BoolLiteralExpr
import gg.sona.msl.ast.BreakStmt
import gg.sona.msl.ast.CallExpr
import gg.sona.msl.ast.CaseStmt
import gg.sona.msl.ast.CastExpr
import gg.sona.msl.ast.CastStyle
import gg.sona.msl.ast.ConditionalExpr
import gg.sona.msl.ast.ConstructExpr
import gg.sona.msl.ast.ContinueStmt
import gg.sona.msl.ast.Decl
import gg.sona.msl.ast.DeclSpecifiers
import gg.sona.msl.ast.DeclStmt
import gg.sona.msl.ast.DoWhileStmt
import gg.sona.msl.ast.EmptyStmt
import gg.sona.msl.ast.EnumDecl
import gg.sona.msl.ast.Enumerator
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.ExprStmt
import gg.sona.msl.ast.ExpressionTemplateArgument
import gg.sona.msl.ast.FieldDecl
import gg.sona.msl.ast.ForStmt
import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.ast.IfStmt
import gg.sona.msl.ast.IndexExpr
import gg.sona.msl.ast.InitListExpr
import gg.sona.msl.ast.IntLiteralExpr
import gg.sona.msl.ast.MemberExpr
import gg.sona.msl.ast.NameExpr
import gg.sona.msl.ast.NamedTypeSyntax
import gg.sona.msl.ast.NamespaceDecl
import gg.sona.msl.ast.ParamDecl
import gg.sona.msl.ast.PointerTypeSyntax
import gg.sona.msl.ast.QualifiedName
import gg.sona.msl.ast.ReferenceTypeSyntax
import gg.sona.msl.ast.ReturnStmt
import gg.sona.msl.ast.SizeofExpr
import gg.sona.msl.ast.StaticAssertDecl
import gg.sona.msl.ast.Stmt
import gg.sona.msl.ast.StringLiteralExpr
import gg.sona.msl.ast.StructDecl
import gg.sona.msl.ast.SwitchStmt
import gg.sona.msl.ast.TemplateArgument
import gg.sona.msl.ast.TemplateParameter
import gg.sona.msl.ast.TranslationUnit
import gg.sona.msl.ast.TypeAliasDecl
import gg.sona.msl.ast.TypeSyntax
import gg.sona.msl.ast.TypeTemplateArgument
import gg.sona.msl.ast.UnaryExpr
import gg.sona.msl.ast.UnaryOperator
import gg.sona.msl.ast.UsingDecl
import gg.sona.msl.ast.UsingNamespaceDecl
import gg.sona.msl.ast.VarDecl
import gg.sona.msl.ast.WhileStmt
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.lang.BuiltinTypeNames
import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.lex.Token
import gg.sona.msl.lex.TokenKind
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation

class Parser(private val tokens: List<Token>, private val diagnostics: Diagnostics) {
    private var index = 0
    private var splitGreater = false
    private var speculationDepth = 0
    private val scopes = ArrayList<HashMap<String, NameKind>>()

    fun parseTranslationUnit(): TranslationUnit {
        scopes.add(HashMap())
        val declarations = ArrayList<Decl>()
        while (!at(TokenKind.Eof)) {
            val before = index
            recover {
                parseDeclaration(DeclContext.Global, declarations)
            }
            if (index == before) advance()
        }
        return TranslationUnit(declarations)
    }

    private val current: Token
        get() = tokens[index.coerceAtMost(tokens.size - 1)]

    private val currentKind: TokenKind
        get() = if (splitGreater) TokenKind.Greater else current.kind

    private fun peek(offset: Int): Token = tokens[(index + offset).coerceAtMost(tokens.size - 1)]

    private fun at(kind: TokenKind): Boolean = currentKind == kind

    private fun atKeyword(keyword: String): Boolean = !splitGreater && current.isIdentifier(keyword)

    private fun advance(): Token {
        val token = current
        splitGreater = false
        if (index < tokens.size - 1) index++
        return token
    }

    private fun accept(kind: TokenKind): Boolean {
        if (!at(kind)) return false
        advance()
        return true
    }

    private fun acceptKeyword(keyword: String): Boolean {
        if (!atKeyword(keyword)) return false
        advance()
        return true
    }

    private fun expect(kind: TokenKind, context: String): Token {
        if (!at(kind)) fail("expected '${kind.spelling}' $context, found '${describe(current)}'")
        return advance()
    }

    private fun expectCloseAngle() {
        when {
            splitGreater -> advance()
            current.kind == TokenKind.Greater -> advance()
            current.kind == TokenKind.GreaterGreater -> splitGreater = true
            else -> fail("expected '>' to close template argument list, found '${describe(current)}'")
        }
    }

    private fun expectIdentifier(context: String): Token {
        if (!at(TokenKind.Identifier)) fail("expected identifier $context, found '${describe(current)}'")
        return advance()
    }

    private fun describe(token: Token): String = if (token.kind == TokenKind.Eof) "end of file" else token.toString()

    private fun fail(message: String, location: SourceLocation = current.location): Nothing {
        if (speculationDepth > 0) throw ParseFailure(true)
        diagnostics.error(location, message)
        throw ParseFailure(false)
    }

    private inline fun <T> speculate(block: () -> T): T? {
        val savedIndex = index
        val savedSplit = splitGreater
        speculationDepth++
        try {
            return block()
        } catch (failure: ParseFailure) {
            if (!failure.speculative) throw failure
            index = savedIndex
            splitGreater = savedSplit
            return null
        } finally {
            speculationDepth--
        }
    }

    private inline fun <T> lookahead(block: () -> T): T? {
        val savedIndex = index
        val savedSplit = splitGreater
        try {
            return speculate(block)
        } finally {
            index = savedIndex
            splitGreater = savedSplit
        }
    }

    private inline fun recover(block: () -> Unit) {
        try {
            block()
        } catch (failure: ParseFailure) {
            if (failure.speculative) throw failure
            synchronize()
        }
    }

    private fun synchronize() {
        var depth = 0
        while (!at(TokenKind.Eof)) {
            when (currentKind) {
                TokenKind.LBrace -> depth++
                TokenKind.RBrace -> {
                    if (depth == 0) return
                    depth--
                    if (depth == 0) {
                        advance()
                        accept(TokenKind.Semicolon)
                        return
                    }
                }

                TokenKind.Semicolon -> if (depth == 0) {
                    advance()
                    return
                }

                else -> Unit
            }
            advance()
        }
    }

    private fun pushScope() {
        scopes.add(HashMap())
    }

    private fun popScope() {
        scopes.removeAt(scopes.size - 1)
    }

    private fun declare(name: String, kind: NameKind) {
        if (speculationDepth == 0) scopes.last()[name] = kind
    }

    private fun lookup(name: String): NameKind? {
        for (i in scopes.indices.reversed()) {
            scopes[i][name]?.let { return it }
        }
        return when {
            BuiltinTypeNames.isTemplate(name) -> NameKind.TemplateType
            BuiltinTypeNames.isTypeName(name) -> NameKind.Type
            name == "as_type" -> NameKind.TemplateFunction
            else -> null
        }
    }

    private fun isTypeName(name: String): Boolean {
        val kind = lookup(name)
        return kind == NameKind.Type || kind == NameKind.TemplateType
    }

    private fun qualifiedNameEnd(start: Int): Int {
        var i = start
        if (tokens.getOrNull(i)?.kind == TokenKind.ColonColon) i++
        if (tokens.getOrNull(i)?.kind != TokenKind.Identifier) return -1
        i++
        while (tokens.getOrNull(i)?.kind == TokenKind.ColonColon && tokens.getOrNull(i + 1)?.kind == TokenKind.Identifier) {
            i += 2
        }
        return i
    }

    private fun typeNameAt(offset: Int): Boolean {
        if (splitGreater) return false
        val start = index + offset
        val end = qualifiedNameEnd(start)
        if (end < 0) return false
        val last = tokens[end - 1].text
        val first = tokens[if (tokens[start].kind == TokenKind.ColonColon) start + 1 else start].text
        if (end - start > 1 && first != "metal" && lookup(first) != NameKind.Namespace && !isTypeName(first)) return false
        return isTypeName(last)
    }

    private fun startsType(offset: Int = 0): Boolean {
        val token = peek(offset)
        if (token.kind == TokenKind.ColonColon || token.kind == TokenKind.Identifier) {
            if (token.kind == TokenKind.Identifier && token.text in TYPE_START_KEYWORDS) return true
            return typeNameAt(offset)
        }
        return false
    }

    private fun startsDeclaration(): Boolean {
        if (at(TokenKind.LBracket) && peek(1).kind == TokenKind.LBracket) return true
        if (!at(TokenKind.Identifier)) return false
        val text = current.text
        if (text in DECLARATION_KEYWORDS) return true
        if (!typeNameAt(0)) return false
        val afterType = lookahead {
            parseTypeSpecifier()
            currentKind
        } ?: return false
        return afterType != TokenKind.LParen && afterType != TokenKind.LBrace && afterType != TokenKind.ColonColon &&
            afterType != TokenKind.Dot
    }

    private fun parseAttributes(into: MutableList<Attribute> = ArrayList()): MutableList<Attribute> {
        while (true) {
            if (at(TokenKind.LBracket) && peek(1).kind == TokenKind.LBracket) {
                advance()
                advance()
                if (!at(TokenKind.RBracket)) {
                    do {
                        into.add(parseAttribute())
                    } while (accept(TokenKind.Comma))
                }
                expect(TokenKind.RBracket, "to close attribute list")
                expect(TokenKind.RBracket, "to close attribute list")
                continue
            }
            if (atKeyword("__attribute__")) {
                advance()
                expect(TokenKind.LParen, "after __attribute__")
                skipBalanced(TokenKind.LParen, TokenKind.RParen, alreadyOpened = true)
                continue
            }
            if (atKeyword("alignas")) {
                val location = advance().location
                expect(TokenKind.LParen, "after alignas")
                val argument = if (startsType()) {
                    SizeofExpr(parseType(), null, true, location)
                } else {
                    parseAssignment()
                }
                expect(TokenKind.RParen, "after alignas argument")
                into.add(Attribute(null, "alignas", listOf(argument), location))
                continue
            }
            return into
        }
    }

    private fun parseAttribute(): Attribute {
        val first = expectIdentifier("in attribute")
        var namespace: String? = null
        var name = first.text
        if (accept(TokenKind.ColonColon)) {
            namespace = name
            name = expectIdentifier("in attribute").text
        }
        val arguments = ArrayList<Expr>()
        if (accept(TokenKind.LParen)) {
            if (!at(TokenKind.RParen)) {
                do {
                    arguments.add(parseAssignment())
                } while (accept(TokenKind.Comma))
            }
            expect(TokenKind.RParen, "to close attribute arguments")
        }
        return Attribute(namespace, name, arguments, first.location)
    }

    private fun skipBalanced(open: TokenKind, close: TokenKind, alreadyOpened: Boolean) {
        var depth = if (alreadyOpened) 1 else 0
        if (!alreadyOpened) {
            expect(open, "")
            depth = 1
        }
        while (depth > 0 && !at(TokenKind.Eof)) {
            if (at(open)) depth++
            if (at(close)) depth--
            advance()
        }
    }

    private fun parseDeclaration(context: DeclContext, out: MutableList<Decl>) {
        if (accept(TokenKind.Semicolon)) return
        val attributes = parseAttributes()
        when {
            atKeyword("template") -> {
                parseTemplateDeclaration(context, out, attributes)
                return
            }

            atKeyword("namespace") || (atKeyword("inline") && peek(1).isIdentifier("namespace")) -> {
                out.add(parseNamespace())
                return
            }

            atKeyword("using") -> {
                parseUsing(out, null)
                return
            }

            atKeyword("static_assert") -> {
                out.add(parseStaticAssert())
                return
            }

            atKeyword("typedef") -> {
                parseTypedef(out)
                return
            }

            context == DeclContext.Struct && current.text in ACCESS_SPECIFIERS && peek(1).kind == TokenKind.Colon -> {
                advance()
                advance()
                return
            }
        }
        parseSimpleDeclaration(context, out, attributes, null)
    }

    private fun parseStaticAssert(): StaticAssertDecl {
        val location = advance().location
        expect(TokenKind.LParen, "after static_assert")
        val condition = parseAssignment()
        var message: String? = null
        if (accept(TokenKind.Comma)) {
            val literal = expect(TokenKind.StringLiteral, "as static_assert message")
            message = literal.text.removeSurrounding("\"")
        }
        expect(TokenKind.RParen, "after static_assert")
        expect(TokenKind.Semicolon, "after static_assert")
        return StaticAssertDecl(condition, message, location)
    }

    private fun parseNamespace(): NamespaceDecl {
        acceptKeyword("inline")
        val location = advance().location
        val name = if (at(TokenKind.Identifier)) advance().text else null
        if (name != null) declare(name, NameKind.Namespace)
        expect(TokenKind.LBrace, "to open namespace")
        val declarations = ArrayList<Decl>()
        while (!at(TokenKind.RBrace) && !at(TokenKind.Eof)) {
            val before = index
            recover { parseDeclaration(DeclContext.Global, declarations) }
            if (index == before) advance()
        }
        expect(TokenKind.RBrace, "to close namespace")
        return NamespaceDecl(name, declarations, location)
    }

    private fun parseUsing(out: MutableList<Decl>, templateParameters: List<TemplateParameter>?) {
        val location = advance().location
        if (acceptKeyword("namespace")) {
            val name = parseQualifiedName()
            expect(TokenKind.Semicolon, "after using-directive")
            out.add(UsingNamespaceDecl(name, location))
            return
        }
        if (at(TokenKind.Identifier) && peek(1).kind == TokenKind.Equal) {
            val name = advance().text
            advance()
            parseAttributes()
            val type = parseType()
            expect(TokenKind.Semicolon, "after alias declaration")
            declare(name, if (templateParameters != null) NameKind.TemplateType else NameKind.Type)
            out.add(TypeAliasDecl(name, type, templateParameters, location))
            return
        }
        val name = parseQualifiedName()
        expect(TokenKind.Semicolon, "after using-declaration")
        out.add(UsingDecl(name, location))
    }

    private fun parseTypedef(out: MutableList<Decl>) {
        advance()
        val base = if (atKeyword("struct") || atKeyword("class") || atKeyword("union")) {
            val struct = parseStructSpecifier(ArrayList(), null)
            if (struct.isDefinition || struct.name != null) out.add(struct)
            val structName = struct.name ?: "__anonymous_struct_${struct.location.offset}"
            NamedTypeSyntax(QualifiedName(listOf(structName)), null, false, false, AddressSpace.Unspecified, struct.location)
        } else if (atKeyword("enum")) {
            val enumDecl = parseEnumSpecifier()
            out.add(enumDecl)
            val enumName = enumDecl.name ?: "__anonymous_enum_${enumDecl.location.offset}"
            NamedTypeSyntax(QualifiedName(listOf(enumName)), null, false, false, AddressSpace.Unspecified, enumDecl.location)
        } else {
            parseTypeSpecifier()
        }
        do {
            val declarator = parseDeclarator(base, requireName = true)
            val type = declarator.type
            val nameToken = declarator.name!!
            val name = nameToken.text
            if (out.lastOrNull() is StructDecl && (out.last() as StructDecl).name == null) {
                val anonymous = out.removeAt(out.size - 1) as StructDecl
                out.add(
                    StructDecl(name, anonymous.members, anonymous.attributes, null, anonymous.isUnion, true, anonymous.location),
                )
                declare(name, NameKind.Type)
                continue
            }
            declare(name, NameKind.Type)
            out.add(TypeAliasDecl(name, type, null, nameToken.location))
        } while (accept(TokenKind.Comma))
        expect(TokenKind.Semicolon, "after typedef")
    }

    private fun parseTemplateDeclaration(context: DeclContext, out: MutableList<Decl>, attributes: MutableList<Attribute>) {
        advance()
        expect(TokenKind.Less, "after 'template'")
        pushScope()
        val parameters = ArrayList<TemplateParameter>()
        try {
            if (!at(TokenKind.Greater)) {
                do {
                    parameters.add(parseTemplateParameter())
                } while (accept(TokenKind.Comma))
            }
            expectCloseAngle()
            parseAttributes(attributes)
            when {
                atKeyword("using") -> parseUsing(out, parameters)
                atKeyword("struct") || atKeyword("class") || atKeyword("union") -> {
                    val struct = parseStructSpecifier(attributes, parameters)
                    expect(TokenKind.Semicolon, "after struct template")
                    out.add(struct)
                }

                else -> parseSimpleDeclaration(context, out, attributes, parameters)
            }
        } finally {
            popScope()
        }
        for (declaration in out.takeLast(1)) {
            when (declaration) {
                is FunctionDecl -> declare(declaration.name, NameKind.TemplateFunction)
                is StructDecl -> declaration.name?.let { declare(it, NameKind.TemplateType) }
                is TypeAliasDecl -> declare(declaration.name, NameKind.TemplateType)
                else -> Unit
            }
        }
    }

    private fun parseTemplateParameter(): TemplateParameter {
        val location = current.location
        if (atKeyword("typename") || atKeyword("class")) {
            advance()
            val name = if (at(TokenKind.Identifier)) advance().text else "__unnamed_${location.offset}"
            declare(name, NameKind.Type)
            val default = if (accept(TokenKind.Equal)) parseType() else null
            return TemplateParameter(name, null, default, null, location)
        }
        val type = parseTypeSpecifier()
        val name = expectIdentifier("as template parameter name").text
        declare(name, NameKind.Value)
        val default = if (accept(TokenKind.Equal)) parseConditional(noGreater = true) else null
        return TemplateParameter(name, type, null, default, location)
    }

    private fun parseSimpleDeclaration(
        context: DeclContext,
        out: MutableList<Decl>,
        attributes: MutableList<Attribute>,
        templateParameters: List<TemplateParameter>?,
    ) {
        var specifiers = DeclSpecifiers.None
        var stage: ShaderStage? = null
        while (true) {
            parseAttributes(attributes)
            val text = if (at(TokenKind.Identifier)) current.text else break
            when (text) {
                "static" -> specifiers += DeclSpecifiers.Static
                "inline", "__inline", "__inline__" -> specifiers += DeclSpecifiers.Inline
                "constexpr" -> specifiers += DeclSpecifiers.Constexpr
                "extern" -> specifiers += DeclSpecifiers.Extern
                "vertex", "fragment", "kernel" -> stage = ShaderStage.fromKeyword(text)
                "visible", "stitchable", "thread_local", "explicit", "virtual", "METAL_FUNC", "METAL_INTERNAL" -> Unit
                else -> break
            }
            advance()
        }
        for (attribute in attributes) {
            if (attribute.namespace == null && attribute.arguments.isEmpty()) {
                ShaderStage.fromKeyword(attribute.name)?.let { stage = it }
            }
        }
        val structName = if (context == DeclContext.Struct) enclosingStructName else null
        if (structName != null && current.isIdentifier(structName) && peek(1).kind == TokenKind.LParen) {
            fail("constructors are not supported")
        }
        if (at(TokenKind.Tilde)) fail("destructors are not supported")
        val base: NamedTypeSyntax = when {
            atKeyword("struct") || atKeyword("class") || atKeyword("union") -> {
                val isElaborated = peek(1).kind == TokenKind.Identifier && peek(2).kind != TokenKind.LBrace &&
                    peek(2).kind != TokenKind.Colon && peek(2).kind != TokenKind.Semicolon &&
                    !(peek(2).isIdentifier("final"))
                if (isElaborated) {
                    advance()
                    parseTypeSpecifier()
                } else {
                    val struct = parseStructSpecifier(attributes, templateParameters)
                    out.add(struct)
                    if (accept(TokenKind.Semicolon)) return
                    val name = struct.name ?: fail("anonymous struct declarators are not supported")
                    NamedTypeSyntax(QualifiedName(listOf(name)), null, false, false, AddressSpace.Unspecified, struct.location)
                }
            }

            atKeyword("enum") -> {
                val isElaborated = peek(1).kind == TokenKind.Identifier && peek(2).kind != TokenKind.LBrace &&
                    peek(2).kind != TokenKind.Colon && peek(2).kind != TokenKind.Semicolon
                if (isElaborated) {
                    advance()
                    parseTypeSpecifier()
                } else {
                    val enumDecl = parseEnumSpecifier()
                    out.add(enumDecl)
                    if (accept(TokenKind.Semicolon)) return
                    val name = enumDecl.name ?: fail("anonymous enum declarators are not supported")
                    NamedTypeSyntax(QualifiedName(listOf(name)), null, false, false, AddressSpace.Unspecified, enumDecl.location)
                }
            }

            else -> parseTypeSpecifier()
        }
        if (accept(TokenKind.Semicolon)) return
        var first = true
        do {
            val declarator = parseDeclarator(base, requireName = true)
            val type = declarator.type
            val nameToken = declarator.name!!
            val name = nameToken.text
            val location = nameToken.location
            if (at(TokenKind.LParen) && isFunctionDeclarator()) {
                if (!first) fail("function declarations cannot share a declaration with other declarators")
                val function = parseFunctionRest(specifiers, stage, type, name, location, attributes, templateParameters)
                out.add(function)
                return
            }
            first = false
            val trailing = ArrayList(attributes)
            trailing.addAll(declarator.attributes)
            when (context) {
                DeclContext.Struct -> {
                    var bitWidth: Expr? = null
                    if (accept(TokenKind.Colon)) bitWidth = parseConditional(noGreater = false)
                    val default = when {
                        accept(TokenKind.Equal) -> parseInitializer()
                        at(TokenKind.LBrace) -> parseInitializer()
                        else -> null
                    }
                    declare(name, NameKind.Value)
                    out.add(FieldDecl(specifiers, type, name, default, bitWidth, trailing, location))
                }

                else -> {
                    val initializer = when {
                        accept(TokenKind.Equal) -> parseInitializer()
                        at(TokenKind.LBrace) -> {
                            val start = current.location
                            val list = parseInitList()
                            ConstructExpr(type, list.elements, true, start)
                        }

                        at(TokenKind.LParen) -> {
                            val start = advance().location
                            val arguments = parseArguments()
                            ConstructExpr(type, arguments, false, start)
                        }

                        else -> null
                    }
                    declare(name, NameKind.Value)
                    out.add(VarDecl(specifiers, type, name, initializer, trailing, location))
                }
            }
        } while (accept(TokenKind.Comma))
        expect(TokenKind.Semicolon, "after declaration")
    }

    private var enclosingStructName: String? = null

    private fun isFunctionDeclarator(): Boolean {
        if (peek(1).kind == TokenKind.RParen) return true
        val saved = index
        index++
        try {
            if (at(TokenKind.LBracket) && peek(1).kind == TokenKind.LBracket) return true
            return startsType() || (at(TokenKind.Identifier) && current.text in PARAMETER_KEYWORDS)
        } finally {
            index = saved
        }
    }

    private fun parseFunctionRest(
        specifiers: DeclSpecifiers,
        stage: ShaderStage?,
        returnType: TypeSyntax,
        name: String,
        location: SourceLocation,
        attributes: MutableList<Attribute>,
        templateParameters: List<TemplateParameter>?,
    ): FunctionDecl {
        declare(name, if (templateParameters != null) NameKind.TemplateFunction else NameKind.Value)
        expect(TokenKind.LParen, "to open parameter list")
        pushScope()
        try {
            val parameters = ArrayList<ParamDecl>()
            if (atKeyword("void") && peek(1).kind == TokenKind.RParen) advance()
            if (!at(TokenKind.RParen)) {
                do {
                    parameters.add(parseParameter())
                } while (accept(TokenKind.Comma))
            }
            expect(TokenKind.RParen, "to close parameter list")
            var isConst = false
            while (true) {
                when {
                    acceptKeyword("const") -> isConst = true
                    acceptKeyword("noexcept") || acceptKeyword("thread") || acceptKeyword("device") ||
                        acceptKeyword("constant") || acceptKeyword("threadgroup") -> Unit

                    at(TokenKind.LBracket) && peek(1).kind == TokenKind.LBracket -> parseAttributes(attributes)
                    else -> break
                }
            }
            val body = when {
                at(TokenKind.LBrace) -> parseBlock(pushNewScope = false)
                accept(TokenKind.Semicolon) -> null
                accept(TokenKind.Equal) -> {
                    expectIdentifier("after '='")
                    expect(TokenKind.Semicolon, "after function declaration")
                    null
                }

                else -> fail("expected function body or ';'")
            }
            return FunctionDecl(
                specifiers,
                stage,
                returnType,
                name,
                parameters,
                body,
                attributes,
                templateParameters,
                isConst,
                location,
            )
        } finally {
            popScope()
        }
    }

    private fun parseParameter(): ParamDecl {
        val attributes = parseAttributes()
        val location = current.location
        val base = parseTypeSpecifier()
        val declarator = parseDeclarator(base, requireName = false)
        val type = declarator.type
        val nameToken = declarator.name
        attributes.addAll(declarator.attributes)
        parseAttributes(attributes)
        val default = if (accept(TokenKind.Equal)) parseAssignment() else null
        nameToken?.let { declare(it.text, NameKind.Value) }
        return ParamDecl(type, nameToken?.text, default, attributes, nameToken?.location ?: location)
    }

    private fun parseStructSpecifier(
        attributes: MutableList<Attribute>,
        templateParameters: List<TemplateParameter>?,
    ): StructDecl {
        val keyword = advance()
        val isUnion = keyword.text == "union"
        parseAttributes(attributes)
        var name: String? = null
        if (at(TokenKind.Identifier) && !atKeyword("final")) name = advance().text
        acceptKeyword("final")
        if (name != null) declare(name, if (templateParameters != null) NameKind.TemplateType else NameKind.Type)
        if (at(TokenKind.Colon)) fail("struct inheritance is not supported")
        if (!at(TokenKind.LBrace)) {
            return StructDecl(name, emptyList(), attributes, templateParameters, isUnion, false, keyword.location)
        }
        advance()
        val members = ArrayList<Decl>()
        val savedStruct = enclosingStructName
        enclosingStructName = name
        pushScope()
        try {
            while (!at(TokenKind.RBrace) && !at(TokenKind.Eof)) {
                val before = index
                recover { parseDeclaration(DeclContext.Struct, members) }
                if (index == before) advance()
            }
        } finally {
            popScope()
            enclosingStructName = savedStruct
        }
        expect(TokenKind.RBrace, "to close struct")
        return StructDecl(name, members, attributes, templateParameters, isUnion, true, keyword.location)
    }

    private fun parseEnumSpecifier(): EnumDecl {
        val location = advance().location
        val scoped = acceptKeyword("class") || acceptKeyword("struct")
        parseAttributes()
        val name = if (at(TokenKind.Identifier)) advance().text else null
        if (name != null) declare(name, NameKind.Type)
        val underlying = if (accept(TokenKind.Colon)) parseTypeSpecifier() else null
        val enumerators = ArrayList<Enumerator>()
        if (accept(TokenKind.LBrace)) {
            while (!at(TokenKind.RBrace)) {
                val token = expectIdentifier("as enumerator")
                val value = if (accept(TokenKind.Equal)) parseConditional(noGreater = false) else null
                enumerators.add(Enumerator(token.text, value, token.location))
                if (!scoped) declare(token.text, NameKind.Value)
                if (!accept(TokenKind.Comma)) break
            }
            expect(TokenKind.RBrace, "to close enum")
        }
        return EnumDecl(name, scoped, underlying, enumerators, location)
    }

    private fun parseQualifiedName(): QualifiedName {
        val global = accept(TokenKind.ColonColon)
        val segments = ArrayList<String>()
        segments.add(expectIdentifier("in name").text)
        while (at(TokenKind.ColonColon) && peek(1).kind == TokenKind.Identifier) {
            advance()
            segments.add(advance().text)
        }
        return QualifiedName(segments, global)
    }

    fun parseType(): TypeSyntax {
        val base = parseTypeSpecifier()
        return parseDeclarator(base, requireName = false).type
    }

    private fun parseTypeSpecifier(): NamedTypeSyntax {
        val location = current.location
        var isConst = false
        var isVolatile = false
        var addressSpace = AddressSpace.Unspecified
        var sign: String? = null
        var lengthModifiers = 0

        fun qualifiers() {
            while (at(TokenKind.Identifier)) {
                val text = current.text
                val space = AddressSpace.fromKeyword(text)
                when {
                    text == "const" -> isConst = true
                    text == "volatile" -> isVolatile = true
                    text == "restrict" || text == "__restrict" || text == "typename" -> Unit
                    space != null -> addressSpace = space
                    else -> return
                }
                advance()
            }
        }

        qualifiers()
        if (atKeyword("struct") || atKeyword("class") || atKeyword("enum") || atKeyword("union")) advance()
        while (atKeyword("signed") || atKeyword("unsigned") || (atKeyword("long") && sign != null) ||
            (atKeyword("short") && sign != null)
        ) {
            val text = advance().text
            if (text == "signed" || text == "unsigned") sign = text else if (text == "long") lengthModifiers++
        }
        val name: QualifiedName
        var templateArguments: List<TemplateArgument>? = null
        if (sign != null && !(at(TokenKind.Identifier) && current.text in INTEGER_BASES)) {
            val base = if (lengthModifiers > 0) "long" else "int"
            name = QualifiedName(listOf(if (sign == "unsigned") UNSIGNED_NAMES.getValue(base) else base))
        } else {
            if (!at(TokenKind.Identifier) && !at(TokenKind.ColonColon)) fail("expected type, found '${describe(current)}'")
            val parsed = parseQualifiedName()
            val simple = parsed.withoutMetalPrefix()
            if (!isTypeName(simple.last) && simple.last !in TYPE_START_KEYWORDS) {
                fail("unknown type name '$parsed'", location)
            }
            name = when {
                sign == null -> parsed
                sign == "unsigned" -> QualifiedName(listOf(UNSIGNED_NAMES[simple.last] ?: "uint"))
                else -> QualifiedName(listOf(SIGNED_NAMES[simple.last] ?: "int"))
            }
            while (atKeyword("long") || atKeyword("int")) advance()
            if (at(TokenKind.Less) && lookup(simple.last) == NameKind.TemplateType) {
                templateArguments = parseTemplateArguments()
            }
        }
        qualifiers()
        return NamedTypeSyntax(name, templateArguments, isConst, isVolatile, addressSpace, location)
    }

    private fun parseTemplateArguments(): List<TemplateArgument> {
        expect(TokenKind.Less, "to open template arguments")
        val arguments = ArrayList<TemplateArgument>()
        if (!at(TokenKind.Greater) && !at(TokenKind.GreaterGreater)) {
            do {
                arguments.add(parseTemplateArgument())
            } while (accept(TokenKind.Comma))
        }
        expectCloseAngle()
        return arguments
    }

    private fun parseTemplateArgument(): TemplateArgument {
        if (startsType()) {
            val type = speculate {
                val parsed = parseType()
                if (!at(TokenKind.Comma) && !at(TokenKind.Greater) && !at(TokenKind.GreaterGreater)) fail("not a type")
                parsed
            }
            if (type != null) return TypeTemplateArgument(type)
        }
        return ExpressionTemplateArgument(parseConditional(noGreater = true))
    }

    private fun parseDeclarator(base: TypeSyntax, requireName: Boolean): Declarator {
        var type = base
        while (true) {
            val location = current.location
            when {
                accept(TokenKind.Star) -> {
                    var isConst = false
                    while (at(TokenKind.Identifier)) {
                        val text = current.text
                        if (text == "const") {
                            isConst = true
                        } else if (text !in POINTER_QUALIFIERS && AddressSpace.fromKeyword(text) == null) {
                            break
                        }
                        advance()
                    }
                    type = PointerTypeSyntax(type, isConst, location)
                }

                accept(TokenKind.Amp) -> type = ReferenceTypeSyntax(type, false, location)
                accept(TokenKind.AmpAmp) -> type = ReferenceTypeSyntax(type, true, location)
                else -> break
            }
        }
        var nameToken: Token? = null
        if (at(TokenKind.Identifier) && current.text !in NON_DECLARATOR_KEYWORDS) {
            nameToken = advance()
        } else if (requireName) {
            fail("expected declarator name, found '${describe(current)}'")
        }
        val attributes = parseAttributes()
        val dimensions = ArrayList<Pair<Expr?, SourceLocation>>()
        while (at(TokenKind.LBracket) && peek(1).kind != TokenKind.LBracket) {
            val location = advance().location
            val size = if (at(TokenKind.RBracket)) null else parseExpression()
            expect(TokenKind.RBracket, "to close array bound")
            dimensions.add(size to location)
        }
        for (i in dimensions.indices.reversed()) {
            type = ArrayTypeSyntax(type, dimensions[i].first, dimensions[i].second)
        }
        parseAttributes(attributes)
        return Declarator(type, nameToken, attributes)
    }

    private fun parseInitializer(): Expr = if (at(TokenKind.LBrace)) parseInitList() else parseAssignment()

    private fun parseInitList(): InitListExpr {
        val location = expect(TokenKind.LBrace, "to open initializer list").location
        val elements = ArrayList<Expr>()
        while (!at(TokenKind.RBrace)) {
            if (at(TokenKind.Dot) && peek(1).kind == TokenKind.Identifier && peek(2).kind == TokenKind.Equal) {
                fail("designated initializers are not supported")
            }
            elements.add(parseInitializer())
            if (!accept(TokenKind.Comma)) break
        }
        expect(TokenKind.RBrace, "to close initializer list")
        return InitListExpr(elements, location)
    }

    private fun parseBlock(pushNewScope: Boolean = true): BlockStmt {
        val location = expect(TokenKind.LBrace, "to open block").location
        if (pushNewScope) pushScope()
        try {
            val statements = ArrayList<Stmt>()
            while (!at(TokenKind.RBrace) && !at(TokenKind.Eof)) {
                val before = index
                recover { statements.add(parseStatement()) }
                if (index == before) advance()
            }
            expect(TokenKind.RBrace, "to close block")
            return BlockStmt(statements, location)
        } finally {
            if (pushNewScope) popScope()
        }
    }

    private fun parseStatement(): Stmt {
        val location = current.location
        if (at(TokenKind.LBracket) && peek(1).kind == TokenKind.LBracket) {
            val attributes = parseAttributes()
            if (atKeyword("for") || atKeyword("while") || atKeyword("do")) return parseLoop(attributes)
            if (startsDeclaration() || at(TokenKind.Identifier) && current.text in DECLARATION_KEYWORDS) {
                val declarations = ArrayList<Decl>()
                parseSimpleDeclaration(DeclContext.Block, declarations, attributes, null)
                return DeclStmt(declarations, location)
            }
            return parseStatement()
        }
        if (at(TokenKind.LBrace)) return parseBlock()
        if (accept(TokenKind.Semicolon)) return EmptyStmt(location)
        if (at(TokenKind.Identifier)) {
            when (current.text) {
                "if" -> return parseIf()
                "for", "while", "do" -> return parseLoop(emptyList())
                "switch" -> {
                    advance()
                    expect(TokenKind.LParen, "after 'switch'")
                    val condition = parseExpression()
                    expect(TokenKind.RParen, "after switch condition")
                    val body = parseStatement()
                    return SwitchStmt(condition, body, location)
                }

                "case" -> {
                    advance()
                    val value = parseConditional(noGreater = false)
                    expect(TokenKind.Colon, "after case value")
                    return CaseStmt(value, parseCaseBody(), location)
                }

                "default" -> if (peek(1).kind == TokenKind.Colon) {
                    advance()
                    advance()
                    return CaseStmt(null, parseCaseBody(), location)
                }

                "break" -> {
                    advance()
                    expect(TokenKind.Semicolon, "after 'break'")
                    return BreakStmt(location)
                }

                "continue" -> {
                    advance()
                    expect(TokenKind.Semicolon, "after 'continue'")
                    return ContinueStmt(location)
                }

                "return" -> {
                    advance()
                    val value = if (at(TokenKind.Semicolon)) null else parseInitializerOrExpression()
                    expect(TokenKind.Semicolon, "after return statement")
                    return ReturnStmt(value, location)
                }

                "goto" -> fail("'goto' is not supported")
            }
        }
        if (startsDeclaration()) {
            val declarations = ArrayList<Decl>()
            if (atKeyword("typedef")) {
                parseTypedef(declarations)
            } else if (atKeyword("using")) {
                parseUsing(declarations, null)
            } else if (atKeyword("static_assert")) {
                declarations.add(parseStaticAssert())
            } else {
                parseSimpleDeclaration(DeclContext.Block, declarations, ArrayList(), null)
            }
            return DeclStmt(declarations, location)
        }
        val expression = parseExpression()
        expect(TokenKind.Semicolon, "after expression")
        return ExprStmt(expression, location)
    }

    private fun parseCaseBody(): Stmt {
        if (at(TokenKind.RBrace)) return EmptyStmt(current.location)
        if (atKeyword("case") || (atKeyword("default") && peek(1).kind == TokenKind.Colon)) return EmptyStmt(current.location)
        return parseStatement()
    }

    private fun parseInitializerOrExpression(): Expr = if (at(TokenKind.LBrace)) parseInitList() else parseExpression()

    private fun parseIf(): Stmt {
        val location = advance().location
        val isConstexpr = acceptKeyword("constexpr")
        expect(TokenKind.LParen, "after 'if'")
        val condition = parseExpression()
        expect(TokenKind.RParen, "after if condition")
        val thenBranch = parseScopedStatement()
        val elseBranch = if (acceptKeyword("else")) parseScopedStatement() else null
        return IfStmt(condition, thenBranch, elseBranch, isConstexpr, location)
    }

    private fun parseScopedStatement(): Stmt {
        pushScope()
        try {
            return parseStatement()
        } finally {
            popScope()
        }
    }

    private fun parseLoop(attributes: List<Attribute>): Stmt {
        val keyword = advance()
        val location = keyword.location
        when (keyword.text) {
            "while" -> {
                expect(TokenKind.LParen, "after 'while'")
                val condition = parseExpression()
                expect(TokenKind.RParen, "after while condition")
                return WhileStmt(condition, parseScopedStatement(), attributes, location)
            }

            "do" -> {
                val body = parseScopedStatement()
                if (!acceptKeyword("while")) fail("expected 'while' after do-while body")
                expect(TokenKind.LParen, "after 'while'")
                val condition = parseExpression()
                expect(TokenKind.RParen, "after do-while condition")
                expect(TokenKind.Semicolon, "after do-while statement")
                return DoWhileStmt(body, condition, attributes, location)
            }
        }
        expect(TokenKind.LParen, "after 'for'")
        pushScope()
        try {
            val initializer: Stmt? = when {
                accept(TokenKind.Semicolon) -> null
                startsDeclaration() -> {
                    val declarations = ArrayList<Decl>()
                    val start = current.location
                    parseSimpleDeclaration(DeclContext.Block, declarations, ArrayList(), null)
                    DeclStmt(declarations, start)
                }

                else -> {
                    val start = current.location
                    val expression = parseExpression()
                    expect(TokenKind.Semicolon, "after for-loop initializer")
                    ExprStmt(expression, start)
                }
            }
            if (at(TokenKind.Colon)) fail("range-based for loops are not supported")
            val condition = if (at(TokenKind.Semicolon)) null else parseExpression()
            expect(TokenKind.Semicolon, "after for-loop condition")
            val increment = if (at(TokenKind.RParen)) null else parseExpression()
            expect(TokenKind.RParen, "after for-loop header")
            val body = parseScopedStatement()
            return ForStmt(initializer, condition, increment, body, attributes, location)
        } finally {
            popScope()
        }
    }

    fun parseExpression(): Expr {
        var expression = parseAssignment()
        while (at(TokenKind.Comma)) {
            val location = advance().location
            expression = BinaryExpr(BinaryOperator.Comma, expression, parseAssignment(), location)
        }
        return expression
    }

    private fun parseAssignment(noGreater: Boolean = false): Expr {
        val target = parseConditional(noGreater)
        val operator = ASSIGNMENT_OPERATORS[currentKind] ?: return target
        val location = advance().location
        val value = if (at(TokenKind.LBrace)) parseInitList() else parseAssignment(noGreater)
        return AssignExpr(operator.operator, target, value, location)
    }

    private fun parseConditional(noGreater: Boolean): Expr {
        val condition = parseBinary(1, noGreater)
        if (!at(TokenKind.Question)) return condition
        val location = advance().location
        val whenTrue = parseExpression()
        expect(TokenKind.Colon, "in conditional expression")
        val whenFalse = parseAssignment(noGreater)
        return ConditionalExpr(condition, whenTrue, whenFalse, location)
    }

    private fun parseBinary(minPrecedence: Int, noGreater: Boolean): Expr {
        var left = parseUnary()
        while (true) {
            val kind = currentKind
            if (noGreater && (kind == TokenKind.Greater || kind == TokenKind.GreaterGreater)) return left
            val operator = BINARY_OPERATORS[kind] ?: return left
            val precedence = precedenceOf(operator)
            if (precedence < minPrecedence) return left
            val location = advance().location
            val right = parseBinary(precedence + 1, noGreater)
            left = BinaryExpr(operator, left, right, location)
        }
    }

    private fun precedenceOf(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LogicalOr -> 1
        BinaryOperator.LogicalAnd -> 2
        BinaryOperator.BitwiseOr -> 3
        BinaryOperator.BitwiseXor -> 4
        BinaryOperator.BitwiseAnd -> 5
        BinaryOperator.Equal, BinaryOperator.NotEqual -> 6
        BinaryOperator.Less, BinaryOperator.Greater, BinaryOperator.LessEqual, BinaryOperator.GreaterEqual -> 7
        BinaryOperator.ShiftLeft, BinaryOperator.ShiftRight -> 8
        BinaryOperator.Add, BinaryOperator.Subtract -> 9
        BinaryOperator.Multiply, BinaryOperator.Divide, BinaryOperator.Remainder -> 10
        BinaryOperator.Comma -> 0
    }

    private fun parseUnary(): Expr {
        val location = current.location
        val prefix = when (currentKind) {
            TokenKind.Plus -> UnaryOperator.Plus
            TokenKind.Minus -> UnaryOperator.Minus
            TokenKind.Bang -> UnaryOperator.LogicalNot
            TokenKind.Tilde -> UnaryOperator.BitwiseNot
            TokenKind.Star -> UnaryOperator.Dereference
            TokenKind.Amp -> UnaryOperator.AddressOf
            TokenKind.PlusPlus -> UnaryOperator.PreIncrement
            TokenKind.MinusMinus -> UnaryOperator.PreDecrement
            else -> null
        }
        if (prefix != null) {
            advance()
            return UnaryExpr(prefix, parseUnary(), location)
        }
        if (atKeyword("sizeof") || atKeyword("alignof")) {
            val isAlignof = advance().text == "alignof"
            if (at(TokenKind.LParen) && startsType(1)) {
                val type = speculate {
                    advance()
                    val parsed = parseType()
                    expect(TokenKind.RParen, "after sizeof type")
                    parsed
                }
                if (type != null) return SizeofExpr(type, null, isAlignof, location)
            }
            return SizeofExpr(null, parseUnary(), isAlignof, location)
        }
        if (at(TokenKind.LParen) && startsType(1)) {
            val cast = speculate {
                advance()
                val type = parseType()
                expect(TokenKind.RParen, "after cast type")
                if (at(TokenKind.LBrace)) fail("compound literal")
                type
            }
            if (cast != null) return CastExpr(CastStyle.CStyle, cast, parseUnary(), location)
        }
        return parsePostfix(parsePrimary())
    }

    private fun parsePostfix(initial: Expr): Expr {
        var expression = initial
        while (true) {
            val location = current.location
            expression = when (currentKind) {
                TokenKind.LParen -> {
                    advance()
                    CallExpr(expression, parseArguments(), location)
                }

                TokenKind.LBracket -> {
                    if (peek(1).kind == TokenKind.LBracket) return expression
                    advance()
                    val index = parseExpression()
                    expect(TokenKind.RBracket, "to close subscript")
                    IndexExpr(expression, index, location)
                }

                TokenKind.Dot, TokenKind.Arrow -> {
                    val isArrow = advance().kind == TokenKind.Arrow
                    acceptKeyword("template")
                    val member = expectIdentifier("after member access").text
                    MemberExpr(expression, member, isArrow, location)
                }

                TokenKind.PlusPlus -> {
                    advance()
                    UnaryExpr(UnaryOperator.PostIncrement, expression, location)
                }

                TokenKind.MinusMinus -> {
                    advance()
                    UnaryExpr(UnaryOperator.PostDecrement, expression, location)
                }

                else -> return expression
            }
        }
    }

    private fun parseArguments(): List<Expr> {
        val arguments = ArrayList<Expr>()
        if (!at(TokenKind.RParen)) {
            do {
                arguments.add(if (at(TokenKind.LBrace)) parseInitList() else parseAssignment())
            } while (accept(TokenKind.Comma))
        }
        expect(TokenKind.RParen, "to close argument list")
        return arguments
    }

    private fun parsePrimary(): Expr {
        val token = current
        val location = token.location
        when (currentKind) {
            TokenKind.Number -> {
                advance()
                return NumberLiterals.parse(token.text, location, diagnostics)
            }

            TokenKind.CharLiteral -> {
                advance()
                return IntLiteralExpr(charValue(token.text), false, false, token.text, location)
            }

            TokenKind.StringLiteral -> {
                advance()
                val builder = StringBuilder(token.text.removeSurrounding("\""))
                while (at(TokenKind.StringLiteral)) builder.append(advance().text.removeSurrounding("\""))
                return StringLiteralExpr(builder.toString(), location)
            }

            TokenKind.LParen -> {
                advance()
                val inner = parseExpression()
                expect(TokenKind.RParen, "to close parenthesized expression")
                return inner
            }

            TokenKind.LBrace -> return parseInitList()
            TokenKind.Identifier, TokenKind.ColonColon -> Unit
            else -> fail("expected expression, found '${describe(token)}'")
        }
        when (token.text) {
            "true", "false" -> {
                advance()
                return BoolLiteralExpr(token.text == "true", location)
            }

            "nullptr", "NULL" -> {
                advance()
                return NameExpr(QualifiedName(listOf("nullptr")), null, location)
            }

            "static_cast", "reinterpret_cast", "const_cast", "as_type" -> if (peek(1).kind == TokenKind.Less) {
                advance()
                val style = when (token.text) {
                    "static_cast" -> CastStyle.Static
                    "reinterpret_cast" -> CastStyle.Reinterpret
                    "const_cast" -> CastStyle.Const
                    else -> CastStyle.AsType
                }
                expect(TokenKind.Less, "after cast keyword")
                val type = parseType()
                expectCloseAngle()
                expect(TokenKind.LParen, "after cast type")
                val operand = parseExpression()
                expect(TokenKind.RParen, "to close cast")
                return CastExpr(style, type, operand, location)
            }
        }
        if (startsType() && !atKeyword("const") && !atKeyword("volatile") && AddressSpace.fromKeyword(current.text) == null) {
            val type = parseTypeSpecifier()
            return when {
                at(TokenKind.LParen) -> {
                    advance()
                    ConstructExpr(type, parseArguments(), false, location)
                }

                at(TokenKind.LBrace) -> ConstructExpr(type, parseInitList().elements, true, location)
                at(TokenKind.ColonColon) -> {
                    advance()
                    val member = expectIdentifier("after '::'").text
                    val typeName = type.name.segments + member
                    NameExpr(QualifiedName(typeName), type.templateArguments, location)
                }

                else -> fail("expected '(' or '{' after type name in expression")
            }
        }
        val name = parseQualifiedName()
        var templateArguments: List<TemplateArgument>? = null
        if (at(TokenKind.Less) && lookup(name.withoutMetalPrefix().last) == NameKind.TemplateFunction) {
            templateArguments = speculate {
                val arguments = parseTemplateArguments()
                if (!at(TokenKind.LParen)) fail("not a template call")
                arguments
            }
        }
        return NameExpr(name, templateArguments, location)
    }

    private fun charValue(text: String): Long {
        val body = text.removePrefix("'").removeSuffix("'")
        if (body.isEmpty()) return 0
        if (body[0] != '\\') return body[0].code.toLong()
        return when (body.getOrNull(1)) {
            'n' -> 10
            't' -> 9
            'r' -> 13
            '0' -> 0
            else -> body.getOrNull(1)?.code?.toLong() ?: 0
        }
    }

    private companion object {
        val TYPE_START_KEYWORDS = setOf(
            "const", "volatile", "signed", "unsigned", "struct", "class", "enum", "union", "typename",
            "device", "constant", "thread", "threadgroup", "threadgroup_imageblock", "ray_data", "object_data",
        )

        val DECLARATION_KEYWORDS = setOf(
            "const", "volatile", "static", "constexpr", "inline", "struct", "class", "union", "enum", "typedef",
            "using", "device", "constant", "thread", "threadgroup", "threadgroup_imageblock", "ray_data",
            "object_data", "signed", "unsigned", "static_assert", "template", "extern", "auto",
        )

        val PARAMETER_KEYWORDS = setOf("void")

        val NON_DECLARATOR_KEYWORDS = setOf("const", "volatile", "override", "final")

        val POINTER_QUALIFIERS = setOf("volatile", "restrict", "__restrict")

        val ACCESS_SPECIFIERS = setOf("public", "private", "protected")

        val INTEGER_BASES = setOf("int", "char", "short", "long")

        val UNSIGNED_NAMES = mapOf("int" to "uint", "char" to "uchar", "short" to "ushort", "long" to "ulong")

        val SIGNED_NAMES = mapOf("int" to "int", "char" to "char", "short" to "short", "long" to "long")

        val BINARY_OPERATORS = mapOf(
            TokenKind.PipePipe to BinaryOperator.LogicalOr,
            TokenKind.AmpAmp to BinaryOperator.LogicalAnd,
            TokenKind.Pipe to BinaryOperator.BitwiseOr,
            TokenKind.Caret to BinaryOperator.BitwiseXor,
            TokenKind.Amp to BinaryOperator.BitwiseAnd,
            TokenKind.EqualEqual to BinaryOperator.Equal,
            TokenKind.BangEqual to BinaryOperator.NotEqual,
            TokenKind.Less to BinaryOperator.Less,
            TokenKind.Greater to BinaryOperator.Greater,
            TokenKind.LessEqual to BinaryOperator.LessEqual,
            TokenKind.GreaterEqual to BinaryOperator.GreaterEqual,
            TokenKind.LessLess to BinaryOperator.ShiftLeft,
            TokenKind.GreaterGreater to BinaryOperator.ShiftRight,
            TokenKind.Plus to BinaryOperator.Add,
            TokenKind.Minus to BinaryOperator.Subtract,
            TokenKind.Star to BinaryOperator.Multiply,
            TokenKind.Slash to BinaryOperator.Divide,
            TokenKind.Percent to BinaryOperator.Remainder,
        )

        val ASSIGNMENT_OPERATORS = mapOf(
            TokenKind.Equal to AssignmentOperator(null),
            TokenKind.PlusEqual to AssignmentOperator(BinaryOperator.Add),
            TokenKind.MinusEqual to AssignmentOperator(BinaryOperator.Subtract),
            TokenKind.StarEqual to AssignmentOperator(BinaryOperator.Multiply),
            TokenKind.SlashEqual to AssignmentOperator(BinaryOperator.Divide),
            TokenKind.PercentEqual to AssignmentOperator(BinaryOperator.Remainder),
            TokenKind.AmpEqual to AssignmentOperator(BinaryOperator.BitwiseAnd),
            TokenKind.PipeEqual to AssignmentOperator(BinaryOperator.BitwiseOr),
            TokenKind.CaretEqual to AssignmentOperator(BinaryOperator.BitwiseXor),
            TokenKind.LessLessEqual to AssignmentOperator(BinaryOperator.ShiftLeft),
            TokenKind.GreaterGreaterEqual to AssignmentOperator(BinaryOperator.ShiftRight),
        )
    }
}
