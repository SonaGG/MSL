package gg.sona.msl.sema

import gg.sona.msl.ast.Attribute
import gg.sona.msl.ast.ConstructExpr
import gg.sona.msl.ast.Decl
import gg.sona.msl.ast.EnumDecl
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.ExpressionTemplateArgument
import gg.sona.msl.ast.FieldDecl
import gg.sona.msl.ast.FunctionDecl
import gg.sona.msl.ast.IntLiteralExpr
import gg.sona.msl.ast.NamespaceDecl
import gg.sona.msl.ast.ParamDecl
import gg.sona.msl.ast.QualifiedName
import gg.sona.msl.ast.StaticAssertDecl
import gg.sona.msl.ast.StructDecl
import gg.sona.msl.ast.TemplateArgument
import gg.sona.msl.ast.TranslationUnit
import gg.sona.msl.ast.TypeAliasDecl
import gg.sona.msl.ast.UsingDecl
import gg.sona.msl.ast.UsingNamespaceDecl
import gg.sona.msl.ast.VarDecl
import gg.sona.msl.hir.ConstValue
import gg.sona.msl.hir.Function
import gg.sona.msl.hir.GlobalVariable
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.hir.Program
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.Variable
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.AutoType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructField
import gg.sona.msl.types.StructType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VoidType

class Sema(val diagnostics: Diagnostics) {
    val globalScope = Scope(null, null, isNamespace = true)
    val constants = HashMap<Variable, ConstValue>()
    val functions = ArrayList<Function>()
    val globals = ArrayList<GlobalVariable>()
    val structs = ArrayList<StructType>()
    val methodSets = HashMap<StructType, MethodSet>()
    val structScopes = HashMap<StructType, Scope>()
    val fieldDefaults = HashMap<StructField, FieldDefault>()

    val types = TypeResolver(this)
    val expressions = ExpressionAnalyzer(this)
    val statements = StatementAnalyzer(this)
    val calls = CallResolver(this)
    val builtins = BuiltinResolver()
    val textures = TextureMethodResolver(diagnostics)
    val atomics = AtomicResolver(diagnostics)
    val samplers = SamplerStateBuilder(this)

    private var anonymousCounter = 0

    fun analyze(unit: TranslationUnit): Program {
        BuiltinEnums.install(globalScope)
        for (declaration in unit.declarations) declare(declaration, globalScope)
        return Program(functions.toList(), globals.toList(), structs.toList())
    }

    fun fold(expression: HExpr): ConstValue? = ConstantFolder(constants).fold(expression)

    fun foldInteger(expression: Expr, scope: Scope, what: String): Long? {
        if (expression is IntLiteralExpr) return expression.value
        val analyzed = expressions.analyze(expression, scope)
        val value = fold(analyzed)
        val scalar = when (value) {
            is ScalarConstant -> value
            is gg.sona.msl.hir.EnumConstant -> return value.value
            else -> null
        }
        if (scalar == null || scalar.kind.isFloat) {
            diagnostics.error(expression.location, "$what must be an integer constant expression")
            return null
        }
        return scalar.asLong
    }

    fun uniqueName(prefix: String): String = "$prefix${anonymousCounter++}"

    fun lookupName(name: QualifiedName, scope: Scope): Symbol? {
        val stripped = name.withoutMetalPrefix()
        if (stripped.isSimple) return scope.lookup(stripped.last)
        return lookupQualified(stripped, scope, null)
    }

    fun lookupQualified(name: QualifiedName, scope: Scope, location: SourceLocation?): Symbol? {
        val segments = name.withoutMetalPrefix().segments
        var current: Symbol? = if (name.global) globalScope.lookupLocal(segments[0]) else scope.lookup(segments[0])
        for (i in 1 until segments.size) {
            val segment = segments[i]
            current = when (current) {
                is NamespaceSymbol -> current.scope.lookupLocal(segment)
                is TypeSymbol -> when (val type = current.type) {
                    is EnumType -> type.values[segment]?.let { EnumeratorSymbol(type, it) }
                    is StructType -> structScopes[type]?.lookupLocal(segment)
                    else -> null
                }

                else -> null
            }
            if (current == null) {
                if (location != null) diagnostics.error(location, "no member named '$segment' in '${segments.take(i).joinToString("::")}'")
                return null
            }
        }
        if (current == null && location != null) diagnostics.error(location, "use of undeclared identifier '$name'")
        return current
    }

    fun declare(declaration: Decl, scope: Scope) {
        when (declaration) {
            is StructDecl -> declareStruct(declaration, scope)
            is EnumDecl -> declareEnum(declaration, scope)
            is TypeAliasDecl -> declareAlias(declaration, scope)
            is NamespaceDecl -> declareNamespace(declaration, scope)
            is UsingNamespaceDecl -> {
                val name = declaration.name.withoutMetalPrefix()
                if (declaration.name.segments == listOf("metal")) return
                when (val symbol = lookupName(name, scope)) {
                    is NamespaceSymbol -> scope.usingNamespaces.add(symbol.scope)
                    else -> diagnostics.error(declaration.location, "'${declaration.name}' is not a namespace")
                }
            }

            is UsingDecl -> {
                val name = declaration.name.withoutMetalPrefix()
                if (name.isSimple) return
                val symbol = lookupQualified(name, scope, null) ?: return
                scope.symbols[name.last] = symbol
            }

            is StaticAssertDecl -> checkStaticAssert(declaration, scope)
            is VarDecl -> declareGlobal(declaration, scope)
            is FunctionDecl -> declareFunction(declaration, scope)
            is FieldDecl, is ParamDecl -> diagnostics.error(declaration.location, "unexpected declaration")
        }
    }

    fun checkStaticAssert(declaration: StaticAssertDecl, scope: Scope) {
        val value = fold(expressions.analyze(declaration.condition, scope)) as? ScalarConstant
        if (value == null) {
            diagnostics.error(declaration.location, "static_assert expression is not an integral constant expression")
        } else if (!value.asBoolean) {
            diagnostics.error(declaration.location, "static assertion failed" + (declaration.message?.let { ": $it" } ?: ""))
        }
    }

    fun declareStruct(declaration: StructDecl, scope: Scope): StructType? {
        if (declaration.templateParameters != null) {
            val templateName = declaration.name ?: run {
                diagnostics.error(declaration.location, "anonymous struct templates are not supported")
                return null
            }
            val existing = scope.lookupLocal(templateName) as? StructTemplateSymbol
            if (existing == null || declaration.isDefinition) scope.symbols[templateName] = StructTemplateSymbol(declaration, scope)
            return null
        }
        val name = declaration.name ?: uniqueName("__anonymous_struct_")
        val existing = (scope.lookupLocal(name) as? TypeSymbol)?.type as? StructType
        val struct = if (existing != null && !existing.isComplete) existing else StructType(name, declaration.location, declaration.isUnion)
        scope.symbols[name] = TypeSymbol(struct)
        if (!declaration.isDefinition) return struct
        val memberScope = Scope(scope, name)
        structScopes[struct] = memberScope
        val methods = MethodSet(struct, memberScope)
        methodSets[struct] = methods
        val fields = ArrayList<StructField>()
        for (member in declaration.members) {
            when (member) {
                is FieldDecl -> {
                    if (member.specifiers.isStatic || member.specifiers.isConstexpr) {
                        declareStaticMember(member, memberScope)
                        continue
                    }
                    val declared = types.resolveDeclared(member.type, memberScope) ?: continue
                    if (declared.isReference) {
                        diagnostics.error(member.location, "reference members are not supported")
                        continue
                    }
                    if (declared.type is ArrayType && (declared.type as ArrayType).isUnsized) {
                        diagnostics.error(member.location, "flexible array members are not supported")
                        continue
                    }
                    val alignment = alignment(member.attributes, memberScope)
                    val field = StructField(member.name, declared.type, member.attributes, fields.size, member.location, alignment)
                    member.defaultValue?.let { fieldDefaults[field] = FieldDefault(it, memberScope) }
                    fields.add(field)
                }

                is FunctionDecl -> if (member.isConstructor) {
                    methods.constructors.add(member)
                } else {
                    methods.methods.getOrPut(member.name) { ArrayList() }.add(member)
                }
                is StaticAssertDecl -> Unit
                else -> declare(member, memberScope)
            }
        }
        struct.explicitAlignment = alignment(declaration.attributes, scope)
        struct.complete(fields)
        structs.add(struct)
        for (member in declaration.members) {
            if (member is StaticAssertDecl) checkStaticAssert(member, memberScope)
        }
        return struct
    }

    private fun declareStaticMember(member: FieldDecl, scope: Scope) {
        val declared = types.resolveDeclared(member.type, scope) ?: return
        val global = GlobalVariable(member.name, declared.type, AddressSpace.Constant, true, member.attributes, member.location)
        val initializer = member.defaultValue
        if (initializer == null) {
            diagnostics.error(member.location, "static member '${member.name}' requires an initializer")
            return
        }
        val value = expressions.coerceInitializer(initializer, declared.type, scope)
        global.initializer = value
        global.constantValue = fold(value)
        scope.symbols[member.name] = VariableSymbol(global)
        globals.add(global)
    }

    private fun alignment(attributes: List<Attribute>, scope: Scope): Int {
        val attribute = attributes.firstOrNull { it.name == "alignas" || it.name == "aligned" } ?: return 0
        val argument = attribute.arguments.firstOrNull() ?: return 0
        return foldInteger(argument, scope, "alignment")?.toInt() ?: 0
    }

    private fun declareEnum(declaration: EnumDecl, scope: Scope) {
        val name = declaration.name ?: uniqueName("__anonymous_enum_")
        val underlying = declaration.underlyingType?.let { types.resolve(it, scope) } ?: ScalarType.Int
        if (underlying !is ScalarType || !underlying.kind.isInteger) {
            diagnostics.error(declaration.location, "enum underlying type must be an integer type")
            return
        }
        val enum = EnumType(name, underlying, declaration.isScoped)
        scope.symbols[name] = TypeSymbol(enum)
        val enumScope = Scope(scope, name)
        var next = 0L
        for (enumerator in declaration.enumerators) {
            val value = enumerator.value?.let { foldInteger(it, enumScope, "enumerator value") } ?: next
            enum.values[enumerator.name] = value
            enumScope.symbols[enumerator.name] = EnumeratorSymbol(enum, value)
            if (!declaration.isScoped) scope.symbols[enumerator.name] = EnumeratorSymbol(enum, value)
            next = value + 1
        }
    }

    private fun declareAlias(declaration: TypeAliasDecl, scope: Scope) {
        if (declaration.templateParameters != null) {
            scope.symbols[declaration.name] = AliasTemplateSymbol(declaration, scope)
            return
        }
        val type = types.resolve(declaration.type, scope) ?: return
        scope.symbols[declaration.name] = TypeSymbol(type)
    }

    fun instantiateAlias(symbol: AliasTemplateSymbol, arguments: List<TemplateArgument>, location: SourceLocation): Type? {
        val parameters = symbol.declaration.templateParameters!!
        val bound = Scope(symbol.scope)
        for ((index, parameter) in parameters.withIndex()) {
            val argument = arguments.getOrNull(index)
            if (parameter.isType) {
                val type = when {
                    argument != null -> types.typeArgument(argument, symbol.scope)
                    parameter.defaultType != null -> types.resolve(parameter.defaultType, bound)
                    else -> null
                } ?: run {
                    diagnostics.error(location, "missing template argument '${parameter.name}'")
                    return null
                }
                bound.symbols[parameter.name] = TypeSymbol(type)
            } else {
                val expression = (argument as? ExpressionTemplateArgument)?.expression ?: parameter.defaultValue
                val value = expression?.let { fold(expressions.analyze(it, symbol.scope)) } ?: run {
                    diagnostics.error(location, "missing template argument '${parameter.name}'")
                    return null
                }
                bound.symbols[parameter.name] = ConstantSymbol(value)
            }
        }
        return types.resolve(symbol.declaration.type, bound)
    }

    fun instantiateStruct(symbol: StructTemplateSymbol, arguments: List<TemplateArgument>, location: SourceLocation): StructType? {
        val declaration = symbol.declaration
        val parameters = declaration.templateParameters!!
        val bound = Scope(symbol.scope)
        val key = ArrayList<Any>()
        for ((index, parameter) in parameters.withIndex()) {
            val argument = arguments.getOrNull(index)
            if (parameter.isType) {
                val type = when {
                    argument != null -> types.typeArgument(argument, symbol.scope)
                    parameter.defaultType != null -> types.resolve(parameter.defaultType, bound)
                    else -> null
                } ?: run {
                    diagnostics.error(location, "missing template argument '${parameter.name}'")
                    return null
                }
                bound.symbols[parameter.name] = TypeSymbol(type)
                key.add(type)
            } else {
                val expression = (argument as? ExpressionTemplateArgument)?.expression ?: parameter.defaultValue
                val value = expression?.let { fold(expressions.analyze(it, symbol.scope)) } ?: run {
                    diagnostics.error(location, "missing template argument '${parameter.name}'")
                    return null
                }
                bound.symbols[parameter.name] = ConstantSymbol(value)
                key.add(value)
            }
        }
        symbol.instances[key]?.let { return it }
        val name = declaration.name + key.joinToString(prefix = "<", postfix = ">")
        val concrete = StructDecl(name, declaration.members, declaration.attributes, null, declaration.isUnion, declaration.isDefinition, declaration.location)
        val struct = declareStruct(concrete, bound) ?: return null
        bound.symbols[declaration.name!!] = TypeSymbol(struct)
        symbol.instances[key] = struct
        return struct
    }

    private fun declareNamespace(declaration: NamespaceDecl, scope: Scope) {
        val name = declaration.name
        if (name == null || name == "metal") {
            for (inner in declaration.declarations) declare(inner, scope)
            return
        }
        val existing = scope.lookupLocal(name) as? NamespaceSymbol
        val namespace = existing?.scope ?: Scope(scope, name, isNamespace = true)
        if (existing == null) scope.symbols[name] = NamespaceSymbol(namespace)
        for (inner in declaration.declarations) declare(inner, namespace)
    }

    private fun declareGlobal(declaration: VarDecl, scope: Scope) {
        val declared = types.resolveDeclared(declaration.type, scope) ?: return
        if (declared.type == SamplerType) {
            declareSampler(declaration, scope)?.let { scope.symbols[declaration.name] = VariableSymbol(it) }
            return
        }
        val space = declared.addressSpace
        val constexpr = declaration.specifiers.isConstexpr
        if (space != AddressSpace.Constant && !(space == AddressSpace.Unspecified && (constexpr || declared.isConst))) {
            diagnostics.error(
                declaration.location,
                "program scope variable '${declaration.name}' must reside in the constant address space",
            )
            return
        }
        if (declared.isReference) {
            diagnostics.error(declaration.location, "program scope references are not supported")
            return
        }
        var type = declared.type
        val functionConstant = declaration.attributes.firstOrNull { it.name == "function_constant" }
        if (functionConstant != null) {
            val index = functionConstant.arguments.firstOrNull()?.let { foldInteger(it, scope, "function constant index") }
            if (index == null || index !in 0..65535) {
                diagnostics.error(declaration.location, "invalid function constant index")
                return
            }
            if (type !is ScalarType && type !is gg.sona.msl.types.VectorType) {
                diagnostics.error(declaration.location, "function constants must be scalars or vectors")
                return
            }
            if (declaration.initializer != null) {
                diagnostics.error(declaration.location, "function constants cannot have an initializer")
            }
            val global = GlobalVariable(declaration.name, type, AddressSpace.Constant, true, declaration.attributes, declaration.location)
            global.functionConstantIndex = index.toInt()
            scope.symbols[declaration.name] = VariableSymbol(global)
            globals.add(global)
            return
        }
        val initializerSyntax = declaration.initializer
        if (initializerSyntax == null) {
            diagnostics.error(declaration.location, "program scope constant '${declaration.name}' requires an initializer")
            return
        }
        type = completeArrayType(type, initializerSyntax)
        if (type == AutoType) type = expressions.analyze(initializerSyntax, scope).type.let { ConversionRules.valueType(it) }
        val global = GlobalVariable(declaration.name, type, AddressSpace.Constant, true, declaration.attributes, declaration.location)
        val initializer = expressions.coerceInitializer(initializerSyntax, type, scope)
        global.initializer = initializer
        global.constantValue = fold(initializer)
        if (global.constantValue == null) {
            diagnostics.error(declaration.location, "initializer of '${declaration.name}' is not a constant expression")
        }
        scope.symbols[declaration.name] = VariableSymbol(global)
        globals.add(global)
    }

    fun declareSampler(declaration: VarDecl, scope: Scope): GlobalVariable? {
        val initializer = declaration.initializer
        if (!declaration.specifiers.isConstexpr) {
            diagnostics.error(declaration.location, "samplers declared in program or function scope must be constexpr")
            return null
        }
        val arguments = when (initializer) {
            is ConstructExpr -> initializer.arguments
            null -> emptyList()
            else -> {
                diagnostics.error(declaration.location, "invalid sampler initializer")
                return null
            }
        }
        val global = GlobalVariable(declaration.name, SamplerType, AddressSpace.Constant, true, declaration.attributes, declaration.location)
        global.samplerState = samplers.build(arguments, scope)
        globals.add(global)
        return global
    }

    fun completeArrayType(type: Type, initializer: Expr): Type {
        if (type !is ArrayType || !type.isUnsized) return type
        val count = when (initializer) {
            is gg.sona.msl.ast.InitListExpr -> initializer.elements.size
            is ConstructExpr -> initializer.arguments.size
            else -> return type
        }
        return ArrayType(type.element, count)
    }

    private fun declareFunction(declaration: FunctionDecl, scope: Scope) {
        if (declaration.templateParameters != null) {
            val symbol = functionSymbol(declaration.name, scope) ?: return
            symbol.overloads.add(TemplateOverload(declaration, scope, null))
            return
        }
        calls.defineFunction(declaration, scope, scope, null, null)
    }

    fun functionSymbol(name: String, scope: Scope): FunctionSymbol? {
        return when (val existing = scope.lookupLocal(name)) {
            is FunctionSymbol -> existing
            null -> FunctionSymbol(name).also { scope.symbols[name] = it }
            else -> {
                diagnostics.error(SourceLocation.NONE, "redefinition of '$name' as a different kind of symbol")
                null
            }
        }
    }

    fun declareParameterVariables(function: Function, scope: Scope) {
        for (parameter in function.parameters) {
            if (parameter.name.isNotEmpty()) scope.symbols[parameter.name] = VariableSymbol(parameter)
        }
    }

    fun parameterVariable(declared: DeclaredType, parameter: ParamDecl, index: Int): LocalVariable {
        val space = when {
            declared.isReference && declared.addressSpace == AddressSpace.Unspecified -> AddressSpace.Thread
            declared.isReference -> declared.addressSpace
            declared.addressSpace == AddressSpace.Unspecified -> AddressSpace.Thread
            else -> declared.addressSpace
        }
        return LocalVariable(
            parameter.name ?: "__param$index",
            declared.type,
            space,
            declared.isConst,
            declared.isReference,
            isParameter = true,
            parameter.attributes,
            parameter.location,
        )
    }

    fun isVoid(type: Type): Boolean = type == VoidType
}
