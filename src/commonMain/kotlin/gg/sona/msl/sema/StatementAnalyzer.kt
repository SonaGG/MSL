package gg.sona.msl.sema

import gg.sona.msl.ast.BlockStmt
import gg.sona.msl.ast.BreakStmt
import gg.sona.msl.ast.CaseStmt
import gg.sona.msl.ast.ContinueStmt
import gg.sona.msl.ast.DeclStmt
import gg.sona.msl.ast.DoWhileStmt
import gg.sona.msl.ast.EmptyStmt
import gg.sona.msl.ast.EnumDecl
import gg.sona.msl.ast.ExprStmt
import gg.sona.msl.ast.ForStmt
import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.ast.IfStmt
import gg.sona.msl.ast.InitListExpr
import gg.sona.msl.ast.ReturnStmt
import gg.sona.msl.ast.StaticAssertDecl
import gg.sona.msl.ast.Stmt
import gg.sona.msl.ast.StructDecl
import gg.sona.msl.ast.SwitchStmt
import gg.sona.msl.ast.TypeAliasDecl
import gg.sona.msl.ast.UsingDecl
import gg.sona.msl.ast.UsingNamespaceDecl
import gg.sona.msl.ast.VarDecl
import gg.sona.msl.ast.WhileStmt
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.Function
import gg.sona.msl.hir.HBlock
import gg.sona.msl.hir.HBreak
import gg.sona.msl.hir.HContinue
import gg.sona.msl.hir.HDeclare
import gg.sona.msl.hir.HExprStatement
import gg.sona.msl.hir.HIf
import gg.sona.msl.hir.HLoop
import gg.sona.msl.hir.HReturn
import gg.sona.msl.hir.HStmt
import gg.sona.msl.hir.HSwitch
import gg.sona.msl.hir.HSwitchCase
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.AutoType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.ErrorType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.VoidType

class StatementAnalyzer(private val sema: Sema) {
    private val diagnostics = sema.diagnostics
    private val contexts = ArrayList<AnalysisContext>()

    private val context: AnalysisContext
        get() = contexts.last()

    fun analyzeFunctionBody(body: BlockStmt, scope: Scope, function: Function): HBlock {
        contexts.add(AnalysisContext(function))
        try {
            return HBlock(body.statements.map { statement(it, scope) }, body.location)
        } finally {
            contexts.removeAt(contexts.size - 1)
        }
    }

    private fun block(statement: BlockStmt, scope: Scope): HBlock {
        val inner = Scope(scope)
        return HBlock(statement.statements.map { statement(it, inner) }, statement.location)
    }

    private fun scoped(statement: Stmt, scope: Scope): HStmt = statement(statement, Scope(scope))

    private fun empty(location: SourceLocation) = HBlock(emptyList(), location)

    fun statement(statement: Stmt, scope: Scope): HStmt = when (statement) {
        is BlockStmt -> block(statement, scope)
        is DeclStmt -> {
            val result = ArrayList<HStmt>()
            for (declaration in statement.declarations) {
                when (declaration) {
                    is VarDecl -> local(declaration, scope)?.let { result.add(it) }
                    is StructDecl, is EnumDecl, is TypeAliasDecl, is UsingNamespaceDecl, is UsingDecl -> sema.declare(declaration, scope)
                    is StaticAssertDecl -> sema.checkStaticAssert(declaration, scope)
                    is FunctionDecl -> diagnostics.error(declaration.location, "nested functions are not allowed")
                    else -> diagnostics.error(declaration.location, "unexpected declaration in function body")
                }
            }
            if (result.size == 1) result[0] else HBlock(result, statement.location)
        }

        is ExprStmt -> HExprStatement(sema.expressions.analyze(statement.expression, scope), statement.location)
        is IfStmt -> ifStatement(statement, scope)
        is ForStmt -> {
            val loopScope = Scope(scope)
            val initializer = statement.initializer?.let { statement(it, loopScope) }
            val condition = statement.condition?.let { sema.expressions.condition(it, loopScope) }
            val body = loopBody(statement.body, loopScope)
            val increment = statement.increment?.let { sema.expressions.analyze(it, loopScope) }
            val loop = HLoop(condition, body, increment, true, statement.location)
            if (initializer == null) loop else HBlock(listOf(initializer, loop), statement.location)
        }

        is WhileStmt -> {
            val condition = sema.expressions.condition(statement.condition, scope)
            HLoop(condition, loopBody(statement.body, scope), null, true, statement.location)
        }

        is DoWhileStmt -> {
            val body = loopBody(statement.body, scope)
            val condition = sema.expressions.condition(statement.condition, scope)
            HLoop(condition, body, null, false, statement.location)
        }

        is SwitchStmt -> switchStatement(statement, scope)
        is CaseStmt -> {
            diagnostics.error(statement.location, "'case' label outside of a switch statement")
            empty(statement.location)
        }

        is BreakStmt -> {
            if (context.breakableDepth == 0) diagnostics.error(statement.location, "'break' outside of a loop or switch")
            HBreak(statement.location)
        }

        is ContinueStmt -> {
            if (context.loopDepth == 0) diagnostics.error(statement.location, "'continue' outside of a loop")
            HContinue(statement.location)
        }

        is ReturnStmt -> returnStatement(statement, scope)
        is EmptyStmt -> empty(statement.location)
    }

    private fun loopBody(body: Stmt, scope: Scope): HStmt {
        context.loopDepth++
        context.breakableDepth++
        try {
            return scoped(body, scope)
        } finally {
            context.loopDepth--
            context.breakableDepth--
        }
    }

    private fun ifStatement(statement: IfStmt, scope: Scope): HStmt {
        val condition = sema.expressions.condition(statement.condition, scope)
        if (statement.isConstexpr) {
            val value = sema.fold(condition) as? ScalarConstant
            if (value == null) {
                diagnostics.error(statement.location, "'if constexpr' condition is not a constant expression")
                return empty(statement.location)
            }
            val taken = if (value.asBoolean) statement.thenBranch else statement.elseBranch
            return taken?.let { scoped(it, scope) } ?: empty(statement.location)
        }
        val thenBranch = scoped(statement.thenBranch, scope)
        val elseBranch = statement.elseBranch?.let { scoped(it, scope) }
        return HIf(condition, thenBranch, elseBranch, statement.location)
    }

    private fun switchStatement(statement: SwitchStmt, scope: Scope): HStmt {
        var selector = sema.expressions.analyze(statement.condition, scope)
        val selectorType = ConversionRules.valueType(selector.type)
        selector = when {
            selectorType == ErrorType -> selector
            selectorType is EnumType -> ConversionRules.convert(selector, selectorType.underlying)
            selectorType is ScalarType && selectorType.kind.isInteger ->
                ConversionRules.convert(selector, ScalarType.of(ConversionRules.promote(selectorType.kind)))

            else -> {
                diagnostics.error(statement.location, "switch condition must be an integer")
                selector
            }
        }
        val body = statement.body as? BlockStmt ?: BlockStmt(listOf(statement.body), statement.body.location)
        val inner = Scope(scope)
        val cases = ArrayList<HSwitchCase>()
        var values = ArrayList<Long>()
        var isDefault = false
        var statements = ArrayList<HStmt>()
        var open = false
        val seen = HashSet<Long>()
        var seenDefault = false
        context.breakableDepth++
        try {
            fun close() {
                if (open) cases.add(HSwitchCase(values, isDefault, statements))
                values = ArrayList()
                isDefault = false
                statements = ArrayList()
                open = false
            }

            for (child in body.statements) {
                var current: Stmt = child
                while (current is CaseStmt) {
                    if (open && statements.isNotEmpty()) close()
                    open = true
                    val labelValue = current.value
                    if (labelValue == null) {
                        if (seenDefault) diagnostics.error(current.location, "multiple default labels in one switch")
                        seenDefault = true
                        isDefault = true
                    } else {
                        val folded = sema.fold(sema.expressions.analyze(labelValue, inner))
                        val value = when (folded) {
                            is ScalarConstant -> if (folded.kind.isInteger || folded.kind.isBool) folded.asLong else null
                            is EnumConstant -> folded.value
                            else -> null
                        }
                        if (value == null) {
                            diagnostics.error(current.location, "case value is not an integer constant expression")
                        } else {
                            if (!seen.add(value)) diagnostics.error(current.location, "duplicate case value '$value'")
                            values.add(value)
                        }
                    }
                    current = current.body
                }
                if (!open) {
                    diagnostics.warning(child.location, "statement will never be executed")
                    continue
                }
                if (current !is EmptyStmt) statements.add(statement(current, inner))
            }
            close()
        } finally {
            context.breakableDepth--
        }
        return HSwitch(selector, cases, statement.location)
    }

    private fun returnStatement(statement: ReturnStmt, scope: Scope): HStmt {
        val function = context.function
        val value = statement.value
        val returnType = function.returnType
        if (returnType == VoidType) {
            if (value != null) {
                val analyzed = sema.expressions.analyze(value, scope)
                if (analyzed.type != VoidType && analyzed.type != ErrorType) {
                    diagnostics.error(statement.location, "void function '${function.name}' should not return a value")
                }
                return HBlock(listOf(HExprStatement(analyzed, statement.location), HReturn(null, statement.location)), statement.location)
            }
            return HReturn(null, statement.location)
        }
        if (value == null) {
            diagnostics.error(statement.location, "non-void function '${function.name}' should return a value")
            return HReturn(null, statement.location)
        }
        return HReturn(sema.expressions.coerceInitializer(value, returnType, scope), statement.location)
    }

    private fun local(declaration: VarDecl, scope: Scope): HStmt? {
        val declared = sema.types.resolveDeclared(declaration.type, scope) ?: return null
        val location = declaration.location
        if (declared.type == SamplerType && !declared.isReference) {
            val sampler = sema.declareSampler(declaration, scope) ?: return null
            scope.symbols[declaration.name] = VariableSymbol(sampler)
            return null
        }
        if (declaration.specifiers.isStatic && !declaration.specifiers.isConstexpr) {
            diagnostics.warning(location, "static local variable '${declaration.name}' is treated as an automatic variable")
        }
        val space = when (declared.addressSpace) {
            AddressSpace.Unspecified, AddressSpace.Thread, AddressSpace.Constant -> AddressSpace.Thread
            AddressSpace.Threadgroup -> AddressSpace.Threadgroup
            else -> {
                diagnostics.error(location, "local variables cannot be declared in the ${declared.addressSpace.spelling} address space")
                return null
            }
        }
        val initializerSyntax = declaration.initializer
        if (space == AddressSpace.Threadgroup && initializerSyntax != null) {
            diagnostics.error(location, "threadgroup variables cannot have an initializer")
            return null
        }
        if (declared.isReference) return reference(declaration, declared, scope)
        var type = if (initializerSyntax != null) sema.completeArrayType(declared.type, initializerSyntax) else declared.type
        if (type is gg.sona.msl.types.ArrayType && type.isUnsized) {
            diagnostics.error(location, "array '${declaration.name}' has unknown size")
            return null
        }
        var initializer = initializerSyntax?.let {
            if (type == AutoType) {
                if (it is InitListExpr) {
                    diagnostics.error(location, "cannot deduce 'auto' from an initializer list")
                    return null
                }
                sema.expressions.analyze(it, scope)
            } else {
                sema.expressions.coerceInitializer(it, type, scope)
            }
        }
        if (initializer == null && type != AutoType && sema.expressions.needsDefaultConstruction(type)) {
            initializer = sema.expressions.construct(type, emptyList(), true, scope, location)
        }
        if (type == AutoType) {
            if (initializer == null) {
                diagnostics.error(location, "declaration of '${declaration.name}' with deduced type 'auto' requires an initializer")
                return null
            }
            type = ConversionRules.valueType(initializer.type)
        }
        if (type == VoidType) {
            diagnostics.error(location, "variable '${declaration.name}' has incomplete type 'void'")
            return null
        }
        val isConst = declared.isConst || declaration.specifiers.isConstexpr || declared.addressSpace == AddressSpace.Constant
        val variable = LocalVariable(declaration.name, type, space, isConst, false, false, declaration.attributes, location)
        if (isConst && initializer != null) sema.fold(initializer)?.let { sema.constants[variable] = it }
        if (isConst && initializer == null) diagnostics.error(location, "const variable '${declaration.name}' requires an initializer")
        if (declaration.specifiers.isConstexpr && initializer != null && sema.constants[variable] == null) {
            diagnostics.error(location, "constexpr variable '${declaration.name}' must be initialized by a constant expression")
        }
        initializer = initializer?.let { if (it.type == ErrorType) it else ConversionRules.convert(it, type) }
        scope.symbols[declaration.name] = VariableSymbol(variable)
        return HDeclare(variable, initializer, location)
    }

    private fun reference(declaration: VarDecl, declared: DeclaredType, scope: Scope): HStmt? {
        val location = declaration.location
        val initializerSyntax = declaration.initializer ?: run {
            diagnostics.error(location, "reference '${declaration.name}' requires an initializer")
            return null
        }
        val initializer = sema.expressions.analyze(initializerSyntax, scope, declared.type)
        if (initializer.type == ErrorType) return null
        val type = if (declared.type == AutoType) ConversionRules.valueType(initializer.type) else declared.type
        if (!initializer.isLvalue || !ConversionRules.sameValueType(initializer.type, type)) {
            if (!declared.isConst) {
                diagnostics.error(location, "non-const reference '${declaration.name}' must bind to an lvalue of type '$type'")
                return null
            }
            val value = sema.expressions.convertImplicitly(initializer, type, "initializer")
            val variable = LocalVariable(declaration.name, type, AddressSpace.Thread, true, false, false, declaration.attributes, location)
            scope.symbols[declaration.name] = VariableSymbol(variable)
            return HDeclare(variable, value, location)
        }
        val variable = LocalVariable(
            declaration.name,
            initializer.type,
            initializer.lvalueAddressSpace,
            declared.isConst || initializer.isConstLvalue,
            isReference = true,
            isParameter = false,
            declaration.attributes,
            location,
        )
        scope.symbols[declaration.name] = VariableSymbol(variable)
        return HDeclare(variable, initializer, location)
    }
}
