package gg.sona.msl.sema

import gg.sona.msl.ast.ArrayTypeSyntax
import gg.sona.msl.ast.ExpressionTemplateArgument
import gg.sona.msl.ast.NamedTypeSyntax
import gg.sona.msl.ast.PointerTypeSyntax
import gg.sona.msl.ast.QualifiedName
import gg.sona.msl.ast.ReferenceTypeSyntax
import gg.sona.msl.ast.TemplateArgument
import gg.sona.msl.ast.TypeSyntax
import gg.sona.msl.ast.TypeTemplateArgument
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.AtomicType
import gg.sona.msl.types.AutoType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SampleOptionKind
import gg.sona.msl.types.SampleOptionType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.TextureAccess
import gg.sona.msl.types.TextureKind
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class TypeResolver(private val sema: Sema) {
    fun resolveDeclared(syntax: TypeSyntax, scope: Scope): DeclaredType? = when (syntax) {
        is ReferenceTypeSyntax -> {
            val inner = resolveDeclared(syntax.referent, scope)
            inner?.let { DeclaredType(it.type, it.isConst, it.addressSpace, isReference = true) }
        }

        is PointerTypeSyntax -> {
            val pointee = resolveDeclared(syntax.pointee, scope)
            when {
                pointee == null -> null
                pointee.isReference -> {
                    sema.diagnostics.error(syntax.location, "pointer to reference is not allowed")
                    null
                }

                else -> {
                    val space = if (pointee.addressSpace == AddressSpace.Unspecified) AddressSpace.Thread else pointee.addressSpace
                    DeclaredType(
                        PointerType(pointee.type, space, pointee.isConst),
                        syntax.isConst,
                        AddressSpace.Unspecified,
                        isReference = false,
                    )
                }
            }
        }

        is ArrayTypeSyntax -> {
            val element = resolveDeclared(syntax.element, scope)
            if (element == null) {
                null
            } else {
                val size = syntax.size?.let { sema.foldInteger(it, scope, "array size") }?.toInt() ?: -1
                if (syntax.size != null && size <= 0) {
                    sema.diagnostics.error(syntax.location, "array size must be positive")
                    null
                } else {
                    DeclaredType(ArrayType(element.type, size), element.isConst, element.addressSpace, isReference = false)
                }
            }
        }

        is NamedTypeSyntax -> resolveNamed(syntax, scope)?.let {
            DeclaredType(it, syntax.isConst, syntax.addressSpace, isReference = false)
        }
    }

    fun resolve(syntax: TypeSyntax, scope: Scope): Type? = resolveDeclared(syntax, scope)?.type

    fun resolveNamed(syntax: NamedTypeSyntax, scope: Scope): Type? {
        val name = syntax.name.withoutMetalPrefix()
        val arguments = syntax.templateArguments
        val location = syntax.location
        if (!name.isSimple) {
            return when (val symbol = sema.lookupQualified(name, scope, location)) {
                is TypeSymbol -> symbol.type
                is StructTemplateSymbol -> sema.instantiateStruct(symbol, arguments ?: emptyList(), location)
                null -> null
                else -> {
                    sema.diagnostics.error(location, "'$name' does not name a type")
                    null
                }
            }
        }
        val simple = name.last
        when (val symbol = scope.lookup(simple)) {
            is TypeSymbol -> {
                if (arguments != null) sema.diagnostics.error(location, "'$simple' is not a template")
                return symbol.type
            }

            is AliasTemplateSymbol -> return sema.instantiateAlias(symbol, arguments ?: emptyList(), location)
            is StructTemplateSymbol -> return sema.instantiateStruct(symbol, arguments ?: emptyList(), location)
            null -> Unit
            else -> {
                if (!isBuiltinName(simple)) {
                    sema.diagnostics.error(location, "'$simple' does not name a type")
                    return null
                }
            }
        }
        return builtin(simple, arguments, scope, location)
    }

    private fun isBuiltinName(name: String): Boolean = gg.sona.msl.lang.BuiltinTypeNames.isTypeName(name)

    private fun builtin(name: String, arguments: List<TemplateArgument>?, scope: Scope, location: SourceLocation): Type? {
        SCALARS[name]?.let { return it }
        when (name) {
            "void" -> return VoidType
            "auto" -> return AutoType
            "sampler" -> return SamplerType
            "double" -> {
                sema.diagnostics.error(location, "'double' is not supported in the Metal Shading Language")
                return null
            }

            "bfloat" -> {
                sema.diagnostics.error(location, "'bfloat' is not supported by this compiler")
                return null
            }
        }
        ATOMICS[name]?.let { return AtomicType(it) }
        SampleOptionKind.fromSpelling(name)?.let { return SampleOptionType.of(it) }
        VECTOR_PATTERN.matchEntire(name)?.let { match ->
            val packed = match.groupValues[1].isNotEmpty()
            val element = SCALARS[match.groupValues[2]] ?: return null
            if (element.kind == ScalarKind.BFloat) return null
            return VectorType.of(element, match.groupValues[3].toInt(), packed)
        }
        MATRIX_PATTERN.matchEntire(name)?.let { match ->
            val element = SCALARS.getValue(match.groupValues[1])
            return MatrixType.of(element, match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }
        TextureKind.fromSpelling(name)?.let { return texture(it, arguments, scope, location) }
        val args = arguments ?: run {
            sema.diagnostics.error(location, "'$name' requires template arguments")
            return null
        }
        when (name) {
            "vec", "packed_vec" -> {
                if (args.size != 2) return arity(name, 2, location)
                val element = scalarArgument(args[0], scope) ?: return null
                val size = integerArgument(args[1], scope)?.toInt() ?: return null
                if (size !in 2..4) {
                    sema.diagnostics.error(location, "vector size must be 2, 3 or 4")
                    return null
                }
                return VectorType.of(element, size, name == "packed_vec")
            }

            "matrix" -> {
                if (args.size != 3) return arity(name, 3, location)
                val element = scalarArgument(args[0], scope) ?: return null
                val columns = integerArgument(args[1], scope)?.toInt() ?: return null
                val rows = integerArgument(args[2], scope)?.toInt() ?: return null
                if (!element.kind.isFloat || columns !in 2..4 || rows !in 2..4) {
                    sema.diagnostics.error(location, "invalid matrix type")
                    return null
                }
                return MatrixType.of(element, columns, rows)
            }

            "array" -> {
                if (args.size != 2) return arity(name, 2, location)
                val element = typeArgument(args[0], scope) ?: return null
                val size = integerArgument(args[1], scope)?.toInt() ?: return null
                return ArrayType(element, size)
            }

            "atomic" -> {
                if (args.size != 1) return arity(name, 1, location)
                val element = scalarArgument(args[0], scope) ?: return null
                return AtomicType(element)
            }
        }
        sema.diagnostics.error(location, "unknown type '$name'")
        return null
    }

    private fun arity(name: String, count: Int, location: SourceLocation): Type? {
        sema.diagnostics.error(location, "'$name' requires $count template arguments")
        return null
    }

    private fun texture(
        kind: TextureKind,
        arguments: List<TemplateArgument>?,
        scope: Scope,
        location: SourceLocation,
    ): Type? {
        val args = arguments ?: emptyList()
        if (args.isEmpty() && !kind.isDepth) {
            sema.diagnostics.error(location, "'${kind.spelling}' requires a sample type")
            return null
        }
        val sampleType = if (args.isEmpty()) ScalarType.Float else scalarArgument(args[0], scope) ?: return null
        if (sampleType.kind == ScalarKind.Bool || sampleType.kind.bits == 64 || sampleType.kind.bits == 8) {
            sema.diagnostics.error(location, "invalid texture sample type '$sampleType'")
            return null
        }
        if (kind.isDepth && !sampleType.kind.isFloat) {
            sema.diagnostics.error(location, "depth textures must use a floating-point sample type")
            return null
        }
        var access = TextureAccess.Sample
        if (args.size >= 2) {
            val argument = args[1] as? ExpressionTemplateArgument
            val value = argument?.let { sema.fold(sema.expressions.analyze(it.expression, scope)) } as? EnumConstant
            if (value == null || value.type != BuiltinEnums.access) {
                sema.diagnostics.error(args[1].location, "expected access qualifier")
                return null
            }
            access = TextureAccess.entries[value.value.toInt()]
        }
        if (args.size > 2) {
            sema.diagnostics.error(location, "too many template arguments for '${kind.spelling}'")
            return null
        }
        if (kind.isBuffer && access == TextureAccess.Sample) access = TextureAccess.Read
        return TextureType(kind, sampleType, access)
    }

    fun typeArgument(argument: TemplateArgument, scope: Scope): Type? = when (argument) {
        is TypeTemplateArgument -> resolve(argument.type, scope)
        is ExpressionTemplateArgument -> {
            val expression = argument.expression
            if (expression is gg.sona.msl.ast.NameExpr) {
                resolveNamed(
                    NamedTypeSyntax(expression.name, expression.templateArguments, false, false, AddressSpace.Unspecified, expression.location),
                    scope,
                )
            } else {
                sema.diagnostics.error(argument.location, "expected a type")
                null
            }
        }
    }

    private fun scalarArgument(argument: TemplateArgument, scope: Scope): ScalarType? {
        val type = typeArgument(argument, scope) ?: return null
        if (type !is ScalarType) {
            sema.diagnostics.error(argument.location, "expected a scalar type, found '$type'")
            return null
        }
        return type
    }

    fun integerArgument(argument: TemplateArgument, scope: Scope): Long? {
        if (argument !is ExpressionTemplateArgument) {
            sema.diagnostics.error(argument.location, "expected a constant expression")
            return null
        }
        val value = sema.fold(sema.expressions.analyze(argument.expression, scope))
        val scalar = value as? ScalarConstant
        if (scalar == null || !scalar.kind.isInteger) {
            sema.diagnostics.error(argument.location, "expected an integer constant expression")
            return null
        }
        return scalar.asLong
    }

    fun templateValue(argument: TemplateArgument, scope: Scope): Any? = when (argument) {
        is TypeTemplateArgument -> resolve(argument.type, scope)
        is ExpressionTemplateArgument -> {
            val expression = argument.expression
            val named = expression as? gg.sona.msl.ast.NameExpr
            val symbol = named?.let { sema.lookupName(it.name, scope) }
            if (symbol is TypeSymbol || (named != null && symbol == null && isBuiltinName(named.name.withoutMetalPrefix().last))) {
                typeArgument(argument, scope)
            } else {
                sema.fold(sema.expressions.analyze(expression, scope))
            }
        }
    }

    private companion object {
        val SCALARS: Map<String, ScalarType> = mapOf(
            "bool" to ScalarType.Bool,
            "char" to ScalarType.Char,
            "uchar" to ScalarType.UChar,
            "short" to ScalarType.Short,
            "ushort" to ScalarType.UShort,
            "int" to ScalarType.Int,
            "uint" to ScalarType.UInt,
            "long" to ScalarType.Long,
            "ulong" to ScalarType.ULong,
            "half" to ScalarType.Half,
            "float" to ScalarType.Float,
            // NB: size_t and ptrdiff_t are 32-bit so ordinary shaders never require 64-bit integer support.
            "size_t" to ScalarType.UInt,
            "ptrdiff_t" to ScalarType.Int,
            "int8_t" to ScalarType.Char,
            "uint8_t" to ScalarType.UChar,
            "int16_t" to ScalarType.Short,
            "uint16_t" to ScalarType.UShort,
            "int32_t" to ScalarType.Int,
            "uint32_t" to ScalarType.UInt,
            "int64_t" to ScalarType.Long,
            "uint64_t" to ScalarType.ULong,
        )

        val ATOMICS: Map<String, ScalarType> = mapOf(
            "atomic_int" to ScalarType.Int,
            "atomic_uint" to ScalarType.UInt,
            "atomic_bool" to ScalarType.Bool,
            "atomic_float" to ScalarType.Float,
            "atomic_long" to ScalarType.Long,
            "atomic_ulong" to ScalarType.ULong,
        )

        val VECTOR_PATTERN = Regex("(packed_)?(bool|char|uchar|short|ushort|int|uint|long|ulong|half|float)([234])")
        val MATRIX_PATTERN = Regex("(half|float)([234])x([234])")
    }
}
