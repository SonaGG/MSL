package gg.sona.msl.sema

import gg.sona.msl.ast.ArrayTypeSyntax
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.ExpressionTemplateArgument
import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.ast.InitListExpr
import gg.sona.msl.ast.NameExpr
import gg.sona.msl.ast.NamedTypeSyntax
import gg.sona.msl.ast.PointerTypeSyntax
import gg.sona.msl.ast.ReferenceTypeSyntax
import gg.sona.msl.ast.TemplateArgument
import gg.sona.msl.ast.TypeSyntax
import gg.sona.msl.ast.TypeTemplateArgument
import gg.sona.msl.hir.ConstValue
import gg.sona.msl.hir.Function
import gg.sona.msl.hir.HAssign
import gg.sona.msl.hir.HBlock
import gg.sona.msl.hir.HCall
import gg.sona.msl.hir.HConstructorCall
import gg.sona.msl.hir.HExprStatement
import gg.sona.msl.hir.HMember
import gg.sona.msl.hir.HStmt
import gg.sona.msl.hir.HVariableRef
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructType
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType

class CallResolver(private val sema: Sema) {
    private val diagnostics = sema.diagnostics

    fun defineFunction(
        declaration: FunctionDecl,
        registrationScope: Scope?,
        bindingScope: Scope,
        owner: MethodSet?,
        thisSpace: AddressSpace?,
        mangledName: String = declaration.name,
    ): ConcreteOverload? {
        val returnDeclared = sema.types.resolveDeclared(declaration.returnType, bindingScope) ?: return null
        if (returnDeclared.isReference) {
            diagnostics.error(declaration.location, "functions returning references are not supported")
            return null
        }
        val parameterTypes = ArrayList<DeclaredType>()
        val parameters = ArrayList<LocalVariable>()
        if (owner != null) {
            val space = thisSpace ?: AddressSpace.Thread
            parameters.add(
                LocalVariable("this", owner.struct, space, declaration.isConstMethod, true, true, emptyList(), declaration.location),
            )
        }
        for ((index, parameter) in declaration.parameters.withIndex()) {
            var declared = sema.types.resolveDeclared(parameter.type, bindingScope) ?: return null
            val type = declared.type
            if (type is ArrayType && !declared.isReference && declaration.stage == null) {
                val space = if (declared.addressSpace == AddressSpace.Unspecified) AddressSpace.Thread else declared.addressSpace
                declared = DeclaredType(PointerType(type.element, space, declared.isConst), false, AddressSpace.Unspecified, false)
            }
            parameterTypes.add(declared)
            parameters.add(sema.parameterVariable(declared, parameter, index))
        }
        if (registrationScope != null) {
            val symbol = sema.functionSymbol(declaration.name, registrationScope) ?: return null
            val existing = symbol.overloads.filterIsInstance<ConcreteOverload>().firstOrNull { candidate ->
                candidate.parameterTypes.size == parameterTypes.size &&
                    candidate.parameterTypes.indices.all { sameParameter(candidate.parameterTypes[it], parameterTypes[it]) }
            }
            if (existing != null) {
                if (declaration.body == null) return existing
                if (existing.function.body != null) {
                    diagnostics.error(declaration.location, "redefinition of '${declaration.name}'")
                    return existing
                }
                existing.function.parameters = parameters
                analyzeBody(existing.function, declaration, bindingScope, owner)
                return existing
            }
            val function = Function(
                declaration.name,
                returnDeclared.type,
                parameters,
                declaration.stage,
                declaration.attributes,
                declaration.location,
            )
            function.mangledName = mangledName
            val overload = ConcreteOverload(declaration, function, parameterTypes)
            symbol.overloads.add(overload)
            sema.functions.add(function)
            if (declaration.body != null) analyzeBody(function, declaration, bindingScope, owner)
            return overload
        }
        val function = Function(
            declaration.name,
            returnDeclared.type,
            parameters,
            declaration.stage,
            declaration.attributes,
            declaration.location,
        )
        function.mangledName = mangledName
        val overload = ConcreteOverload(declaration, function, parameterTypes)
        sema.functions.add(function)
        if (declaration.body != null) analyzeBody(function, declaration, bindingScope, owner)
        return overload
    }

    private fun sameParameter(a: DeclaredType, b: DeclaredType): Boolean =
        a.type == b.type && a.isReference == b.isReference && a.addressSpace == b.addressSpace

    private fun analyzeBody(function: Function, declaration: FunctionDecl, bindingScope: Scope, owner: MethodSet?) {
        val scope = Scope(bindingScope)
        scope.function = function
        if (owner != null) {
            scope.methodOwner = owner
            scope.thisVariable = function.parameters.first()
        }
        sema.declareParameterVariables(function, scope)
        function.isBeingAnalyzed = true
        val prologue = if (declaration.isConstructor && owner != null) constructorPrologue(declaration, owner, function, scope) else emptyList()
        val body = sema.statements.analyzeFunctionBody(declaration.body!!, scope, function)
        function.body = if (prologue.isEmpty()) body else HBlock(prologue + body.statements, body.location)
        function.isBeingAnalyzed = false
    }

    private fun constructorPrologue(declaration: FunctionDecl, owner: MethodSet, function: Function, scope: Scope): List<HStmt> {
        val self = function.parameters.first()
        val statements = ArrayList<HStmt>()
        val initializers = declaration.memberInitializers.associateBy { it.name }
        for (name in initializers.keys) {
            if (owner.struct.field(name) == null) diagnostics.error(initializers.getValue(name).location, "'$name' is not a member of '${owner.struct}'")
        }
        for (field in owner.struct.fields) {
            val initializer = initializers[field.name]
            val value = when {
                initializer != null -> sema.expressions.construct(field.type, initializer.arguments, initializer.braced, scope, initializer.location)
                field in sema.fieldDefaults || sema.expressions.needsDefaultConstruction(field.type) ->
                    sema.expressions.defaultValue(field, declaration.location)

                else -> continue
            }
            val target = HMember(HVariableRef(self, declaration.location), field, declaration.location)
            statements.add(HExprStatement(HAssign(target, value, declaration.location), declaration.location))
        }
        return statements
    }

    fun resolveConstructor(methods: MethodSet, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr? {
        val analyzed = arguments.map { if (it is InitListExpr) null else sema.expressions.analyze(it, scope) }
        val candidates = ArrayList<OverloadCandidate>()
        for (declaration in methods.constructors) {
            if (declaration.templateParameters != null) {
                diagnostics.error(declaration.location, "constructor templates are not supported")
                continue
            }
            val overload = methods.constructorInstances[declaration] ?: run {
                val name = methods.struct.name
                val defined = defineFunction(declaration, null, methods.scope, methods, AddressSpace.Thread, "$name::$name") ?: continue
                methods.constructorInstances[declaration] = defined
                defined
            }
            val cost = cost(overload, analyzed, skipThis = true) ?: continue
            candidates.add(OverloadCandidate(overload, cost))
        }
        return finish(methods.struct.name, candidates, emptyList(), arguments, analyzed, scope, location, methods.struct)
    }

    fun resolveCall(
        symbol: FunctionSymbol,
        templateArguments: List<TemplateArgument>?,
        arguments: List<Expr>,
        scope: Scope,
        location: SourceLocation,
    ): HExpr? {
        val analyzed = arguments.map { if (it is InitListExpr) null else sema.expressions.analyze(it, scope) }
        val candidates = ArrayList<OverloadCandidate>()
        for (overload in symbol.overloads) {
            val concrete = when (overload) {
                is ConcreteOverload -> if (templateArguments != null) null else overload
                is TemplateOverload -> instantiate(overload, templateArguments, analyzed, scope, location)
            } ?: continue
            val cost = cost(concrete, analyzed, skipThis = false) ?: continue
            candidates.add(OverloadCandidate(concrete, cost))
        }
        return finish(symbol.name, candidates, emptyList(), arguments, analyzed, scope, location)
    }

    fun resolveStaticCall(
        methods: MethodSet,
        name: String,
        templateArguments: List<TemplateArgument>?,
        arguments: List<Expr>,
        scope: Scope,
        location: SourceLocation,
    ): HExpr? {
        val analyzed = arguments.map { if (it is InitListExpr) null else sema.expressions.analyze(it, scope) }
        val candidates = ArrayList<OverloadCandidate>()
        for (declaration in methods.methods[name].orEmpty()) {
            if (!declaration.specifiers.isStatic) continue
            val concrete = if (declaration.templateParameters != null) {
                val template = methods.templates.getOrPut(declaration) { TemplateOverload(declaration, methods.scope, null) }
                instantiate(template, templateArguments, analyzed, scope, location, "${methods.struct.name}::")
            } else {
                if (templateArguments != null) continue
                methods.staticInstances[declaration] ?: defineFunction(declaration, null, methods.scope, null, null, "${methods.struct.name}::$name")
                    ?.also { methods.staticInstances[declaration] = it }
            } ?: continue
            val cost = cost(concrete, analyzed, skipThis = false) ?: continue
            candidates.add(OverloadCandidate(concrete, cost))
        }
        return finish(name, candidates, emptyList(), arguments, analyzed, scope, location)
    }

    fun resolveMethodCall(
        base: HExpr,
        methods: MethodSet,
        name: String,
        arguments: List<Expr>,
        scope: Scope,
        location: SourceLocation,
        templateArguments: List<TemplateArgument>? = null,
    ): HExpr? {
        val declarations = methods.methods[name] ?: run {
            diagnostics.error(location, "no member named '$name' in '${methods.struct}'")
            return null
        }
        val space = if (base.isLvalue) base.lvalueAddressSpace else AddressSpace.Thread
        val analyzed = arguments.map { if (it is InitListExpr) null else sema.expressions.analyze(it, scope) }
        val candidates = ArrayList<OverloadCandidate>()
        for (declaration in declarations) {
            if (declaration.specifiers.isStatic) continue
            if (declaration.templateParameters != null) {
                val template = methods.templates.getOrPut(declaration) { TemplateOverload(declaration, methods.scope, methods.struct) }
                val concrete = instantiate(template, templateArguments, analyzed, scope, location, "${methods.struct.name}::", space) ?: continue
                val cost = cost(concrete, analyzed, skipThis = true) ?: continue
                candidates.add(OverloadCandidate(concrete, cost))
                continue
            }
            if (templateArguments != null) continue
            val overload = methods.instances[declaration to space] ?: run {
                val defined = defineFunction(
                    declaration,
                    null,
                    methods.scope,
                    methods,
                    space,
                    "${methods.struct.name}::${declaration.name}",
                ) ?: continue
                methods.instances[declaration to space] = defined
                defined
            }
            val cost = cost(overload, analyzed, skipThis = true) ?: continue
            candidates.add(OverloadCandidate(overload, cost))
        }
        return finish(name, candidates, listOf(base), arguments, analyzed, scope, location)
    }

    private fun finish(
        name: String,
        candidates: List<OverloadCandidate>,
        leading: List<HExpr>,
        arguments: List<Expr>,
        analyzed: List<HExpr?>,
        scope: Scope,
        location: SourceLocation,
        constructed: StructType? = null,
    ): HExpr? {
        if (candidates.isEmpty()) {
            val types = analyzed.joinToString { it?.type?.toString() ?: "initializer list" }
            diagnostics.error(location, "no matching function for call to '$name' with arguments ($types)")
            return null
        }
        val best = candidates.minOf { it.cost }
        val winners = candidates.filter { it.cost == best }
        if (winners.size > 1 && winners.map { it.overload.function }.distinct().size > 1) {
            diagnostics.error(location, "call to '$name' is ambiguous")
            return null
        }
        val overload = winners.first().overload
        val function = overload.function
        if (function.isBeingAnalyzed) {
            diagnostics.error(location, "recursive call to '$name' is not allowed in Metal shaders")
            return null
        }
        val skip = leading.size + if (constructed != null) 1 else 0
        val converted = ArrayList<HExpr>(leading)
        for ((index, declared) in overload.parameterTypes.withIndex()) {
            val argument = analyzed.getOrNull(index)
            val syntax = arguments.getOrNull(index)
            val parameter = function.parameters[index + skip]
            converted.add(
                when {
                    syntax is InitListExpr -> sema.expressions.coerceInitializer(syntax, declared.type, scope)
                    argument != null && declared.isReference && !declared.isConst -> argument
                    argument != null -> sema.expressions.convertImplicitly(argument, declared.type, "argument")
                    else -> {
                        val default = overload.declaration.parameters[index].defaultValue
                        if (default == null) {
                            diagnostics.error(location, "missing argument for parameter '${parameter.name}'")
                            return null
                        }
                        sema.expressions.coerceInitializer(default, declared.type, scope)
                    }
                },
            )
        }
        if (constructed != null) return HConstructorCall(function, converted, constructed, location)
        return HCall(function, converted, location)
    }

    private fun cost(overload: ConcreteOverload, arguments: List<HExpr?>, skipThis: Boolean): Int? {
        val parameters = overload.parameterTypes
        if (arguments.size > parameters.size) return null
        val declaration = overload.declaration
        for (index in arguments.size until parameters.size) {
            if (declaration.parameters[index].defaultValue == null) return null
        }
        var total = 0
        for ((index, argument) in arguments.withIndex()) {
            val parameter = parameters[index]
            if (argument == null) {
                total += ConversionRules.CONVERSION
                continue
            }
            if (parameter.isReference && !parameter.isConst) {
                if (!argument.isLvalue || argument.isConstLvalue) return null
                if (!ConversionRules.sameValueType(argument.type, parameter.type)) return null
                val space = if (parameter.addressSpace == AddressSpace.Unspecified) AddressSpace.Thread else parameter.addressSpace
                if (argument.lvalueAddressSpace != space) return null
                continue
            }
            total += ConversionRules.implicitCost(argument.type, parameter.type) ?: return null
        }
        return if (skipThis) total else total
    }

    private fun instantiate(
        overload: TemplateOverload,
        explicit: List<TemplateArgument>?,
        arguments: List<HExpr?>,
        scope: Scope,
        location: SourceLocation,
        prefix: String = "",
        thisSpace: AddressSpace? = null,
    ): ConcreteOverload? {
        val declaration = overload.declaration
        val parameters = declaration.templateParameters!!
        val bindings = HashMap<String, Any>()
        explicit?.forEachIndexed { index, argument ->
            val parameter = parameters.getOrNull(index) ?: return null
            bindings[parameter.name] = sema.types.templateValue(argument, scope) ?: return null
        }
        val templateNames = parameters.map { it.name }.toSet()
        for ((index, parameter) in declaration.parameters.withIndex()) {
            val argument = arguments.getOrNull(index) ?: continue
            if (!unify(parameter.type, ConversionRules.valueType(argument.type), templateNames, bindings)) return null
        }
        val bound = Scope(overload.scope)
        val key = ArrayList<Any>()
        for (parameter in parameters) {
            var value = bindings[parameter.name]
            if (value == null) {
                value = if (parameter.isType) {
                    parameter.defaultType?.let { sema.types.resolve(it, bound) }
                } else {
                    parameter.defaultValue?.let { sema.fold(sema.expressions.analyze(it, bound)) }
                } ?: return null
            }
            bound.symbols[parameter.name] = when (value) {
                is Type -> TypeSymbol(value)
                is ConstValue -> ConstantSymbol(value)
                else -> return null
            }
            key.add(value)
        }
        if (thisSpace != null) key.add(thisSpace)
        overload.instances[key]?.let { return it }
        val mangled = prefix + declaration.name + key.joinToString(prefix = "<", postfix = ">")
        val concrete = defineFunction(declaration, null, bound, overload.owner?.let { sema.methodSets[it] }, thisSpace, mangled)
            ?: run {
                diagnostics.note(location, "while instantiating '$mangled'")
                return null
            }
        overload.instances[key] = concrete
        return concrete
    }

    private fun unify(syntax: TypeSyntax, type: Type, names: Set<String>, bindings: HashMap<String, Any>): Boolean {
        return when (syntax) {
            is ReferenceTypeSyntax -> unify(syntax.referent, type, names, bindings)
            is PointerTypeSyntax -> {
                val pointee = when (type) {
                    is PointerType -> type.pointee
                    is ArrayType -> type.element
                    else -> return true
                }
                unify(syntax.pointee, pointee, names, bindings)
            }

            is ArrayTypeSyntax -> {
                if (type !is ArrayType) return true
                val size = syntax.size
                if (size is NameExpr && size.name.isSimple && size.name.last in names) {
                    bindValue(size.name.last, type.size.toLong(), bindings)
                }
                unify(syntax.element, type.element, names, bindings)
            }

            is NamedTypeSyntax -> {
                val name = syntax.name.withoutMetalPrefix()
                val arguments = syntax.templateArguments
                if (name.isSimple && name.last in names && arguments == null) {
                    val existing = bindings[name.last]
                    if (existing == null) bindings[name.last] = type
                    return true
                }
                if (arguments == null) return true
                when (name.last) {
                    "vec", "packed_vec" -> if (type is VectorType) {
                        unifyArgument(arguments.getOrNull(0), type.element, names, bindings)
                        unifyValue(arguments.getOrNull(1), type.size.toLong(), names, bindings)
                    }

                    "matrix" -> if (type is MatrixType) {
                        unifyArgument(arguments.getOrNull(0), type.element, names, bindings)
                        unifyValue(arguments.getOrNull(1), type.columns.toLong(), names, bindings)
                        unifyValue(arguments.getOrNull(2), type.rows.toLong(), names, bindings)
                    }

                    "array" -> if (type is ArrayType) {
                        unifyArgument(arguments.getOrNull(0), type.element, names, bindings)
                        unifyValue(arguments.getOrNull(1), type.size.toLong(), names, bindings)
                    }

                    else -> if (type is TextureType) unifyArgument(arguments.getOrNull(0), type.sampleType, names, bindings)
                }
                true
            }
        }
    }

    private fun unifyArgument(argument: TemplateArgument?, type: Type, names: Set<String>, bindings: HashMap<String, Any>) {
        when (argument) {
            is TypeTemplateArgument -> unify(argument.type, type, names, bindings)
            is ExpressionTemplateArgument -> {
                val expression = argument.expression
                if (expression is NameExpr && expression.name.isSimple && expression.name.last in names) {
                    bindings.getOrPut(expression.name.last) { type }
                }
            }

            null -> Unit
        }
    }

    private fun unifyValue(argument: TemplateArgument?, value: Long, names: Set<String>, bindings: HashMap<String, Any>) {
        val expression = (argument as? ExpressionTemplateArgument)?.expression as? NameExpr ?: return
        if (expression.name.isSimple && expression.name.last in names) bindValue(expression.name.last, value, bindings)
    }

    private fun bindValue(name: String, value: Long, bindings: HashMap<String, Any>) {
        bindings.getOrPut(name) { ScalarConstant.of(ScalarType.Int, value) }
    }
}
