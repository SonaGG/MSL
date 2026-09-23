package gg.sona.msl.sema

import gg.sona.msl.hir.HExpr
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class BuiltinResolver {
    fun resolve(name: String, arguments: List<HExpr>, location: SourceLocation): HExpr? {
        val signatures = BuiltinLibrary.lookup(name) ?: return null
        for (lenient in listOf(false, true)) {
            for (signature in signatures) {
                match(signature, arguments, location, lenient)?.let { return it }
            }
        }
        return null
    }

    private fun match(
        signature: BuiltinSignature,
        arguments: List<HExpr>,
        location: SourceLocation,
        lenient: Boolean,
    ): HExpr? {
        if (signature.parameters.size != arguments.size) return null
        val typeArgument = inferTypeArgument(signature, arguments, lenient) ?: return null
        val converted = ArrayList<HExpr>(arguments.size)
        for ((index, parameter) in signature.parameters.withIndex()) {
            val argument = arguments[index]
            val target = parameterType(parameter, typeArgument) ?: return null
            when (parameter.kind) {
                PatternKind.ReferenceToT, PatternKind.ReferenceToIntOfT -> {
                    if (!argument.isLvalue || argument.isConstLvalue) return null
                    if (!ConversionRules.sameValueType(argument.type, target)) return null
                    converted.add(argument)
                }

                else -> {
                    ConversionRules.implicitCost(argument.type, target) ?: return null
                    converted.add(ConversionRules.convert(argument, target))
                }
            }
        }
        val resultType = parameterType(signature.result, typeArgument) ?: return null
        return signature.build(BuiltinCall(converted, typeArgument, resultType, location))
    }

    private fun inferTypeArgument(signature: BuiltinSignature, arguments: List<HExpr>, lenient: Boolean): Type? {
        val generic = signature.parameters.indices.filter {
            val kind = signature.parameters[it].kind
            kind == PatternKind.T || kind == PatternKind.ReferenceToT
        }
        if (generic.isEmpty()) return VoidType
        val types = generic.map { ConversionRules.valueType(arguments[it].type) }
        if (signature.shape == ShapeClass.Matrix || signature.shape == ShapeClass.SquareMatrix) {
            val matrix = types[0] as? MatrixType ?: return null
            if (types.any { it != matrix }) return null
            if (signature.shape == ShapeClass.SquareMatrix && matrix.columns != matrix.rows) return null
            if (!signature.elements.accepts(matrix.element.kind)) return null
            return matrix
        }
        var vectorSize = 0
        var vectorKind: ScalarKind? = null
        var scalarKind: ScalarKind? = null
        for (type in types) {
            when (type) {
                is VectorType -> {
                    if (vectorSize != 0 && vectorSize != type.size) return null
                    if (vectorKind != null && vectorKind != type.element.kind) {
                        if (!lenient) return null
                        vectorKind = ConversionRules.usualArithmetic(vectorKind, type.element.kind)
                    } else {
                        vectorKind = type.element.kind
                    }
                    vectorSize = type.size
                }

                else -> {
                    val scalar = ConversionRules.scalarOf(type) ?: return null
                    val kind = scalar.kind
                    scalarKind = when {
                        scalarKind == null -> kind
                        scalarKind == kind -> kind
                        else -> ConversionRules.usualArithmetic(scalarKind, kind)
                    }
                }
            }
        }
        var kind = vectorKind ?: scalarKind ?: return null
        if (!signature.elements.accepts(kind)) {
            if (!lenient || signature.elements != ElementClass.Float) return null
            kind = ScalarKind.Float
        }
        val result: Type = if (vectorSize > 0) VectorType.of(kind, vectorSize) else ScalarType.of(kind)
        return when (signature.shape) {
            ShapeClass.Scalar -> if (vectorSize > 0) null else result
            ShapeClass.Vector -> if (vectorSize > 0) result else null
            ShapeClass.Vector3 -> if (vectorSize == 3) result else null
            else -> result
        }
    }

    private fun parameterType(parameter: BuiltinParameter, typeArgument: Type): Type? = when (parameter.kind) {
        PatternKind.T, PatternKind.ReferenceToT -> typeArgument
        PatternKind.ScalarOfT -> when (typeArgument) {
            is MatrixType -> typeArgument.element
            else -> ConversionRules.scalarOf(typeArgument)
        }

        PatternKind.BoolOfT -> ConversionRules.withElement(typeArgument, ScalarType.Bool)
        PatternKind.IntOfT, PatternKind.ReferenceToIntOfT -> ConversionRules.withElement(typeArgument, ScalarType.Int)
        PatternKind.UIntOfT -> ConversionRules.withElement(typeArgument, ScalarType.UInt)
        PatternKind.Fixed -> parameter.fixed
        PatternKind.TransposedT -> (typeArgument as? MatrixType)?.transposed()
    }
}
