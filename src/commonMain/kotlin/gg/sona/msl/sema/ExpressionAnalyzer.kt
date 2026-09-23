package gg.sona.msl.sema

import gg.sona.msl.ast.AssignExpr
import gg.sona.msl.ast.BinaryExpr
import gg.sona.msl.ast.BinaryOperator
import gg.sona.msl.ast.BoolLiteralExpr
import gg.sona.msl.ast.CallExpr
import gg.sona.msl.ast.CastExpr
import gg.sona.msl.ast.CastStyle
import gg.sona.msl.ast.ConditionalExpr
import gg.sona.msl.ast.ConstructExpr
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.FloatLiteralExpr
import gg.sona.msl.ast.FloatSuffix
import gg.sona.msl.ast.IndexExpr
import gg.sona.msl.ast.InitListExpr
import gg.sona.msl.ast.IntLiteralExpr
import gg.sona.msl.ast.MemberExpr
import gg.sona.msl.ast.NameExpr
import gg.sona.msl.ast.QualifiedName
import gg.sona.msl.ast.SizeofExpr
import gg.sona.msl.ast.StringLiteralExpr
import gg.sona.msl.ast.UnaryExpr
import gg.sona.msl.ast.UnaryOperator
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.HAddressOf
import gg.sona.msl.hir.HAssign
import gg.sona.msl.hir.HBinary
import gg.sona.msl.hir.HBinaryOperator
import gg.sona.msl.hir.HBitcast
import gg.sona.msl.hir.HComma
import gg.sona.msl.hir.HCompoundAssign
import gg.sona.msl.hir.HConditional
import gg.sona.msl.hir.HConstruct
import gg.sona.msl.hir.HConvert
import gg.sona.msl.hir.HDeref
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HIncDec
import gg.sona.msl.hir.HIndex
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.HLogical
import gg.sona.msl.hir.HMember
import gg.sona.msl.hir.HPointerOffset
import gg.sona.msl.hir.HSwizzle
import gg.sona.msl.hir.HUnary
import gg.sona.msl.hir.HUnaryOperator
import gg.sona.msl.hir.HVariableRef
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.ZeroConstant
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.lang.BuiltinTypeNames
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.AtomicType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.ErrorType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.NullPointerType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SampleOptionKind
import gg.sona.msl.types.SampleOptionType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructType
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.TypeLayout
import gg.sona.msl.types.VectorType

class ExpressionAnalyzer(private val sema: Sema) {
    private val diagnostics = sema.diagnostics

    fun error(location: SourceLocation, message: String): HExpr {
        diagnostics.error(location, message)
        return errorExpression(location)
    }

    private fun errorExpression(location: SourceLocation): HExpr = HLiteral(ZeroConstant(ErrorType), location)

    private fun isError(expression: HExpr): Boolean = expression.type == ErrorType

    fun analyze(expression: Expr, scope: Scope, expected: Type? = null): HExpr = when (expression) {
        is IntLiteralExpr -> {
            val type = when {
                expression.isLong && expression.isUnsigned -> ScalarType.ULong
                expression.isLong -> ScalarType.Long
                expression.isUnsigned -> ScalarType.UInt
                else -> ScalarType.Int
            }
            HLiteral(ScalarConstant.of(type, expression.value), expression.location)
        }

        is FloatLiteralExpr -> {
            val type = if (expression.suffix == FloatSuffix.Half) ScalarType.Half else ScalarType.Float
            HLiteral(ScalarConstant.of(type, expression.value), expression.location)
        }

        is BoolLiteralExpr -> HLiteral(ScalarConstant.of(ScalarType.Bool, expression.value), expression.location)
        is StringLiteralExpr -> error(expression.location, "string literals are not supported in expressions")
        is NameExpr -> name(expression, scope)
        is UnaryExpr -> unary(expression, scope)
        is BinaryExpr -> binary(expression, scope)
        is AssignExpr -> assign(expression, scope)
        is ConditionalExpr -> conditional(expression, scope, expected)
        is CallExpr -> call(expression, scope)
        is MemberExpr -> member(expression, scope)
        is IndexExpr -> index(expression, scope)
        is CastExpr -> cast(expression, scope)
        is ConstructExpr -> {
            val type = sema.types.resolve(expression.type, scope)
            if (type == null) {
                errorExpression(expression.location)
            } else {
                construct(type, expression.arguments, expression.isBraced, scope, expression.location)
            }
        }

        is InitListExpr -> if (expected != null) {
            construct(expected, expression.elements, true, scope, expression.location)
        } else {
            error(expression.location, "cannot deduce the type of an initializer list")
        }

        is SizeofExpr -> sizeof(expression, scope)
    }

    fun condition(expression: Expr, scope: Scope): HExpr = toBool(analyze(expression, scope))

    fun toBool(value: HExpr): HExpr {
        if (isError(value)) return value
        val type = ConversionRules.valueType(value.type)
        return when {
            type == ScalarType.Bool -> value
            type is ScalarType || (type is EnumType && !type.isScoped) -> ConversionRules.convert(value, ScalarType.Bool)
            type is PointerType -> error(value.location, "pointer to bool conversions are not supported")
            else -> error(value.location, "value of type '$type' is not contextually convertible to 'bool'")
        }
    }

    fun coerceInitializer(initializer: Expr, target: Type, scope: Scope): HExpr {
        if (initializer is InitListExpr) return construct(target, initializer.elements, true, scope, initializer.location)
        val value = analyze(initializer, scope, target)
        return convertImplicitly(value, target, "initializer")
    }

    fun convertImplicitly(value: HExpr, target: Type, context: String): HExpr {
        if (isError(value) || target == ErrorType) return value
        val isNull = value is HLiteral && value.type == NullPointerType
        if (ConversionRules.implicitCost(value.type, target, isNull) == null) {
            return error(value.location, "cannot convert '${value.type}' to '$target' in $context")
        }
        return ConversionRules.convert(value, target)
    }

    private fun name(expression: NameExpr, scope: Scope): HExpr {
        val name = expression.name.withoutMetalPrefix()
        val location = expression.location
        if (name.isSimple) {
            when (val simple = name.last) {
                "this" -> return thisPointer(scope, location)
                "nullptr" -> return HLiteral(ZeroConstant(NullPointerType), location)
                else -> {
                    val symbol = lookupSimple(simple, scope)
                    if (symbol == null) {
                        BuiltinConstants.lookup(simple)?.let { return HLiteral(it, location) }
                        return error(location, "use of undeclared identifier '$simple'")
                    }
                    return symbolExpression(symbol, simple, scope, location)
                }
            }
        }
        val symbol = sema.lookupQualified(name, scope, location) ?: return errorExpression(location)
        return symbolExpression(symbol, name.toString(), scope, location)
    }

    fun lookupSimple(name: String, scope: Scope): Symbol? {
        var current: Scope? = scope
        while (current != null) {
            current.lookupLocal(name)?.let { return it }
            val owner = current.methodOwner
            if (owner != null) {
                owner.struct.field(name)?.let { return FieldSymbol(it) }
                owner.scope.lookupLocal(name)?.let { return it }
            }
            current = current.parent
        }
        return null
    }

    private fun symbolExpression(symbol: Symbol, name: String, scope: Scope, location: SourceLocation): HExpr = when (symbol) {
        is VariableSymbol -> HVariableRef(symbol.variable, location)
        is ConstantSymbol -> HLiteral(symbol.value, location)
        is EnumeratorSymbol -> HLiteral(EnumConstant(symbol.enumType, symbol.value), location)
        is FieldSymbol -> {
            val (self, _) = scope.enclosingThis() ?: return error(location, "invalid use of member '$name'")
            HMember(HVariableRef(self, location), symbol.field, location)
        }

        is TypeSymbol -> error(location, "'$name' is a type, not a value")
        is FunctionSymbol -> error(location, "function '$name' cannot be used as a value")
        is NamespaceSymbol -> error(location, "'$name' is a namespace, not a value")
        is AliasTemplateSymbol -> error(location, "'$name' is a type template, not a value")
    }

    private fun thisPointer(scope: Scope, location: SourceLocation): HExpr {
        val (self, _) = scope.enclosingThis() ?: return error(location, "'this' is only valid inside member functions")
        return HAddressOf(HVariableRef(self, location), PointerType(self.type, self.addressSpace, self.isConst), location)
    }

    private fun unary(expression: UnaryExpr, scope: Scope): HExpr {
        val location = expression.location
        val operand = analyze(expression.operand, scope)
        if (isError(operand)) return operand
        val type = ConversionRules.valueType(operand.type)
        return when (expression.operator) {
            UnaryOperator.Plus -> {
                if (!type.isNumericScalarOrVector && type !is MatrixType) return error(location, "invalid operand to unary '+'")
                promoted(operand)
            }

            UnaryOperator.Minus -> {
                if (!type.isNumericScalarOrVector && type !is MatrixType) return error(location, "invalid operand to unary '-'")
                val value = promoted(operand)
                HUnary(HUnaryOperator.Negate, value, ConversionRules.valueType(value.type), location)
            }

            UnaryOperator.LogicalNot -> when {
                type is VectorType && type.element.kind.isBool -> HUnary(HUnaryOperator.LogicalNot, operand, type, location)
                type is VectorType -> {
                    val zero = HLiteral(ZeroConstant(type), location)
                    val boolType = VectorType.of(ScalarType.Bool, type.size)
                    HBinary(HBinaryOperator.Equal, operand, zero, boolType, location)
                }

                else -> HUnary(HUnaryOperator.LogicalNot, toBool(operand), ScalarType.Bool, location)
            }

            UnaryOperator.BitwiseNot -> {
                if (!type.isIntegerScalarOrVector && type != ScalarType.Bool) return error(location, "invalid operand to '~'")
                val value = promoted(operand)
                HUnary(HUnaryOperator.BitwiseNot, value, ConversionRules.valueType(value.type), location)
            }

            UnaryOperator.Dereference -> when (type) {
                is PointerType -> HDeref(operand, location)
                is ArrayType -> HIndex(operand, zeroIndex(location), type.element, location)
                else -> error(location, "indirection requires a pointer operand")
            }

            UnaryOperator.AddressOf -> {
                if (!operand.isLvalue) return error(location, "cannot take the address of an rvalue")
                HAddressOf(operand, PointerType(operand.type, operand.lvalueAddressSpace, operand.isConstLvalue), location)
            }

            UnaryOperator.PreIncrement, UnaryOperator.PreDecrement, UnaryOperator.PostIncrement, UnaryOperator.PostDecrement -> {
                checkModifiable(operand, location) ?: return errorExpression(location)
                if (!type.isNumericScalarOrVector && type !is PointerType) {
                    return error(location, "invalid operand to increment or decrement")
                }
                val increment = expression.operator == UnaryOperator.PreIncrement || expression.operator == UnaryOperator.PostIncrement
                val prefix = expression.operator == UnaryOperator.PreIncrement || expression.operator == UnaryOperator.PreDecrement
                HIncDec(operand, increment, prefix, location)
            }
        }
    }

    private fun zeroIndex(location: SourceLocation) = HLiteral(ScalarConstant.of(ScalarType.Int, 0L), location)

    private fun promoted(value: HExpr): HExpr {
        val type = ConversionRules.valueType(value.type)
        if (type !is ScalarType) return value
        val promoted = ConversionRules.promote(type.kind)
        return if (promoted == type.kind) value else ConversionRules.convert(value, ScalarType.of(promoted))
    }

    private fun checkModifiable(target: HExpr, location: SourceLocation): Unit? {
        if (!target.isLvalue) {
            diagnostics.error(location, "expression is not assignable")
            return null
        }
        if (target.isConstLvalue || target.lvalueAddressSpace == AddressSpace.Constant) {
            diagnostics.error(location, "cannot assign to a read-only location")
            return null
        }
        if (target.type is TextureType || target.type is gg.sona.msl.types.SamplerType) {
            diagnostics.error(location, "textures and samplers cannot be assigned")
            return null
        }
        return Unit
    }

    private fun binary(expression: BinaryExpr, scope: Scope): HExpr {
        val location = expression.location
        when (expression.operator) {
            BinaryOperator.Comma -> {
                return HComma(analyze(expression.left, scope), analyze(expression.right, scope), location)
            }

            BinaryOperator.LogicalAnd, BinaryOperator.LogicalOr -> {
                val rawLeft = analyze(expression.left, scope)
                val rawRight = analyze(expression.right, scope)
                val leftVector = ConversionRules.valueType(rawLeft.type) as? VectorType
                val rightVector = ConversionRules.valueType(rawRight.type) as? VectorType
                if (leftVector != null || rightVector != null) {
                    val size = leftVector?.size ?: rightVector!!.size
                    val boolType = VectorType.of(ScalarType.Bool, size)
                    val operator = if (expression.operator == BinaryOperator.LogicalAnd) HBinaryOperator.BitwiseAnd else HBinaryOperator.BitwiseOr
                    val left = vectorCondition(rawLeft, boolType) ?: return errorExpression(location)
                    val right = vectorCondition(rawRight, boolType) ?: return errorExpression(location)
                    return HBinary(operator, left, right, boolType, location)
                }
                val left = toBool(rawLeft)
                val right = toBool(rawRight)
                return HLogical(expression.operator == BinaryOperator.LogicalAnd, left, right, ScalarType.Bool, location)
            }

            else -> Unit
        }
        val left = analyze(expression.left, scope)
        val right = analyze(expression.right, scope)
        if (isError(left) || isError(right)) return errorExpression(location)
        return binaryOperation(OPERATORS.getValue(expression.operator), left, right, location)
    }

    fun binaryOperation(operator: HBinaryOperator, left: HExpr, right: HExpr, location: SourceLocation): HExpr {
        val leftType = ConversionRules.valueType(left.type)
        val rightType = ConversionRules.valueType(right.type)
        if (leftType is PointerType || rightType is PointerType) return pointerOperation(operator, left, right, location)
        if (leftType is EnumType || rightType is EnumType) {
            if (leftType == rightType && (operator == HBinaryOperator.BitwiseOr || operator == HBinaryOperator.BitwiseAnd ||
                    operator == HBinaryOperator.BitwiseXor)
            ) {
                return HBinary(operator, left, right, leftType, location)
            }
            if (leftType == rightType && operator.isComparison) {
                return HBinary(operator, left, right, ScalarType.Bool, location)
            }
            val leftValue = if (leftType is EnumType) enumToInteger(left, leftType) ?: return invalidOperands(operator, leftType, rightType, location) else left
            val rightValue = if (rightType is EnumType) enumToInteger(right, rightType) ?: return invalidOperands(operator, leftType, rightType, location) else right
            return binaryOperation(operator, leftValue, rightValue, location)
        }
        if (leftType is MatrixType || rightType is MatrixType) return matrixOperation(operator, left, right, leftType, rightType, location)
        val leftValid = leftType is ScalarType || leftType is VectorType
        val rightValid = rightType is ScalarType || rightType is VectorType
        if (!leftValid || !rightValid) return invalidOperands(operator, leftType, rightType, location)
        if (operator == HBinaryOperator.ShiftLeft || operator == HBinaryOperator.ShiftRight) {
            if (!leftType.isIntegerScalarOrVector && leftType != ScalarType.Bool || !rightType.isIntegerScalarOrVector && rightType != ScalarType.Bool) {
                return invalidOperands(operator, leftType, rightType, location)
            }
            val element = ScalarType.of(ConversionRules.promote(ConversionRules.scalarOf(leftType)!!.kind))
            val resultType: Type = when {
                leftType is VectorType -> VectorType.of(element, leftType.size)
                rightType is VectorType -> VectorType.of(element, rightType.size)
                else -> element
            }
            return HBinary(operator, ConversionRules.convert(left, resultType), ConversionRules.convert(right, resultType), resultType, location)
        }
        val leftElement = ConversionRules.scalarOf(leftType)!!.kind
        val rightElement = ConversionRules.scalarOf(rightType)!!.kind
        val bothBool = leftElement.isBool && rightElement.isBool
        val boolOperator = operator == HBinaryOperator.BitwiseAnd || operator == HBinaryOperator.BitwiseOr ||
            operator == HBinaryOperator.BitwiseXor || operator == HBinaryOperator.Equal || operator == HBinaryOperator.NotEqual
        val operationType: Type = if (bothBool && boolOperator) {
            commonShape(leftType, rightType, ScalarType.Bool) ?: return invalidOperands(operator, leftType, rightType, location)
        } else {
            commonType(leftType, rightType) ?: return invalidOperands(operator, leftType, rightType, location)
        }
        val element = ConversionRules.scalarOf(operationType)!!.kind
        val valid = when (operator) {
            HBinaryOperator.Remainder -> element.isInteger
            HBinaryOperator.BitwiseAnd, HBinaryOperator.BitwiseOr, HBinaryOperator.BitwiseXor -> element.isInteger || element.isBool
            HBinaryOperator.Equal, HBinaryOperator.NotEqual -> true
            else -> !element.isBool
        }
        if (!valid) return invalidOperands(operator, leftType, rightType, location)
        val resultType = if (operator.isComparison) ConversionRules.withElement(operationType, ScalarType.Bool) else operationType
        return HBinary(
            operator,
            ConversionRules.convert(left, operationType),
            ConversionRules.convert(right, operationType),
            resultType,
            location,
        )
    }

    private fun vectorCondition(value: HExpr, target: VectorType): HExpr? {
        val type = ConversionRules.valueType(value.type)
        return when {
            type == target -> value
            type is ScalarType -> HConstruct(target, listOf(toBool(value)), value.location)
            type is VectorType && type.size == target.size -> {
                val zero = HLiteral(ZeroConstant(type), value.location)
                HBinary(HBinaryOperator.NotEqual, value, zero, target, value.location)
            }

            else -> {
                diagnostics.error(value.location, "invalid vector operand of type '$type' to a logical operator")
                null
            }
        }
    }

    private fun enumToInteger(value: HExpr, type: EnumType): HExpr? =
        if (type.isScoped) null else ConversionRules.convert(value, type.underlying)

    private fun invalidOperands(operator: HBinaryOperator, left: Type, right: Type, location: SourceLocation): HExpr =
        error(location, "invalid operands to binary expression ('$left' ${operator.spelling} '$right')")

    private fun commonShape(left: Type, right: Type, element: ScalarType): Type? = when {
        left is VectorType && right is VectorType -> if (left.size == right.size) VectorType.of(element, left.size) else null
        left is VectorType -> VectorType.of(element, left.size)
        right is VectorType -> VectorType.of(element, right.size)
        else -> element
    }

    fun commonType(left: Type, right: Type): Type? {
        val a = ConversionRules.valueType(left)
        val b = ConversionRules.valueType(right)
        return when {
            a is VectorType && b is VectorType -> if (a == b) a else null
            a is VectorType && b is ScalarType -> a
            a is ScalarType && b is VectorType -> b
            a is ScalarType && b is ScalarType -> ScalarType.of(ConversionRules.usualArithmetic(a.kind, b.kind))
            else -> null
        }
    }

    private fun pointerOperation(operator: HBinaryOperator, left: HExpr, right: HExpr, location: SourceLocation): HExpr {
        val leftType = ConversionRules.valueType(left.type)
        val rightType = ConversionRules.valueType(right.type)
        return when {
            leftType is PointerType && rightType.isIntegerScalarOrVector && rightType is ScalarType &&
                (operator == HBinaryOperator.Add || operator == HBinaryOperator.Subtract) -> {
                val offset = if (operator == HBinaryOperator.Subtract) {
                    HUnary(HUnaryOperator.Negate, ConversionRules.convert(right, ScalarType.Int), ScalarType.Int, location)
                } else {
                    right
                }
                HPointerOffset(left, offset, location)
            }

            rightType is PointerType && leftType is ScalarType && leftType.kind.isInteger && operator == HBinaryOperator.Add ->
                HPointerOffset(right, left, location)

            leftType is PointerType && rightType is PointerType && operator.isComparison ->
                HBinary(operator, left, right, ScalarType.Bool, location)

            leftType is PointerType && rightType is PointerType && operator == HBinaryOperator.Subtract ->
                HBinary(operator, left, right, ScalarType.Long, location)

            else -> invalidOperands(operator, leftType, rightType, location)
        }
    }

    private fun matrixOperation(
        operator: HBinaryOperator,
        left: HExpr,
        right: HExpr,
        leftType: Type,
        rightType: Type,
        location: SourceLocation,
    ): HExpr {
        when (operator) {
            HBinaryOperator.Multiply -> {
                if (leftType is MatrixType && rightType is MatrixType) {
                    if (leftType.columns != rightType.rows || leftType.element != rightType.element) {
                        return invalidOperands(operator, leftType, rightType, location)
                    }
                    return HBinary(operator, left, right, MatrixType.of(leftType.element, rightType.columns, leftType.rows), location)
                }
                if (leftType is MatrixType && rightType is VectorType) {
                    if (rightType.size != leftType.columns) return invalidOperands(operator, leftType, rightType, location)
                    val vector = ConversionRules.convert(right, VectorType.of(leftType.element, rightType.size))
                    return HBinary(operator, left, vector, leftType.columnType, location)
                }
                if (leftType is VectorType && rightType is MatrixType) {
                    if (leftType.size != rightType.rows) return invalidOperands(operator, leftType, rightType, location)
                    val vector = ConversionRules.convert(left, VectorType.of(rightType.element, leftType.size))
                    return HBinary(operator, vector, right, rightType.rowType, location)
                }
                if (leftType is MatrixType && rightType is ScalarType) {
                    return HBinary(operator, left, ConversionRules.convert(right, leftType.element), leftType, location)
                }
                if (leftType is ScalarType && rightType is MatrixType) {
                    return HBinary(operator, ConversionRules.convert(left, rightType.element), right, rightType, location)
                }
            }

            HBinaryOperator.Add, HBinaryOperator.Subtract -> if (leftType == rightType) {
                return HBinary(operator, left, right, leftType, location)
            }

            HBinaryOperator.Divide -> if (leftType is MatrixType && rightType is ScalarType) {
                return HBinary(operator, left, ConversionRules.convert(right, leftType.element), leftType, location)
            }

            else -> Unit
        }
        return invalidOperands(operator, leftType, rightType, location)
    }

    private fun assign(expression: AssignExpr, scope: Scope): HExpr {
        val location = expression.location
        val target = analyze(expression.target, scope)
        if (isError(target)) return target
        checkModifiable(target, location) ?: return errorExpression(location)
        val operator = expression.operator
        if (operator == null) {
            val value = coerceInitializer(expression.value, target.type, scope)
            return HAssign(target, value, location)
        }
        val value = analyze(expression.value, scope)
        if (isError(value)) return value
        val operation = binaryOperation(OPERATORS.getValue(operator), target, value, location)
        return when (operation) {
            is HBinary -> {
                val resultType = ConversionRules.valueType(operation.type)
                val targetType = ConversionRules.valueType(target.type)
                if (resultType != targetType && ConversionRules.implicitCost(resultType, targetType) == null) {
                    return error(location, "cannot assign '$resultType' to '$targetType'")
                }
                HCompoundAssign(operation.operator, target, operation.right, resultType, location)
            }

            is HPointerOffset -> HCompoundAssign(HBinaryOperator.Add, target, operation.offset, target.type, location)
            else -> operation
        }
    }

    private fun conditional(expression: ConditionalExpr, scope: Scope, expected: Type?): HExpr {
        val location = expression.location
        val rawCondition = analyze(expression.condition, scope)
        val whenTrue = analyze(expression.whenTrue, scope, expected)
        val whenFalse = analyze(expression.whenFalse, scope, expected)
        if (isError(rawCondition) || isError(whenTrue) || isError(whenFalse)) return errorExpression(location)
        val conditionType = ConversionRules.valueType(rawCondition.type)
        val trueType = ConversionRules.valueType(whenTrue.type)
        val falseType = ConversionRules.valueType(whenFalse.type)
        val type = when {
            trueType == falseType -> trueType
            trueType.isNumericScalarOrVector && falseType.isNumericScalarOrVector -> commonType(trueType, falseType)
            trueType is PointerType && falseType == NullPointerType -> trueType
            falseType is PointerType && trueType == NullPointerType -> falseType
            else -> null
        } ?: return error(location, "incompatible operand types ('$trueType' and '$falseType') in conditional expression")
        if (conditionType is VectorType) {
            if (!conditionType.element.kind.isBool) return error(location, "vector conditions must be boolean vectors")
            val selectType = if (type is ScalarType) VectorType.of(type, conditionType.size) else type
            if (selectType !is VectorType || selectType.size != conditionType.size) {
                return error(location, "vector condition and operands must have the same number of components")
            }
            return HConditional(
                rawCondition,
                ConversionRules.convert(whenTrue, selectType),
                ConversionRules.convert(whenFalse, selectType),
                selectType,
                location,
            )
        }
        return HConditional(
            toBool(rawCondition),
            ConversionRules.convert(whenTrue, type),
            ConversionRules.convert(whenFalse, type),
            type,
            location,
        )
    }

    private fun call(expression: CallExpr, scope: Scope): HExpr {
        val location = expression.location
        return when (val callee = expression.callee) {
            is MemberExpr -> methodCall(callee, expression.arguments, scope, location)
            is NameExpr -> namedCall(callee, expression.arguments, scope, location)
            else -> error(location, "called object is not a function")
        }
    }

    private fun namedCall(callee: NameExpr, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        val name = callee.name.withoutMetalPrefix()
        val symbol = if (name.isSimple) lookupSimple(name.last, scope) else sema.lookupName(name, scope)
        when (symbol) {
            is FunctionSymbol -> {
                return sema.calls.resolveCall(symbol, callee.templateArguments, arguments, scope, location)
                    ?: errorExpression(location)
            }

            is TypeSymbol -> return construct(symbol.type, arguments, false, scope, location)
            null -> Unit
            else -> return error(location, "called object '$name' is not a function")
        }
        val builtinName = builtinName(name) ?: return error(location, "use of undeclared identifier '$name'")
        if (BuiltinTypeNames.isTypeName(builtinName)) {
            val type = sema.types.resolveNamed(
                gg.sona.msl.ast.NamedTypeSyntax(QualifiedName(listOf(builtinName)), callee.templateArguments, false, false, AddressSpace.Unspecified, location),
                scope,
            ) ?: return errorExpression(location)
            return construct(type, arguments, false, scope, location)
        }
        val analyzed = arguments.map { analyze(it, scope) }
        if (analyzed.any { isError(it) }) return errorExpression(location)
        if (builtinName == "is_function_constant_defined") {
            val reference = analyzed.singleOrNull() as? gg.sona.msl.hir.HVariableRef
            val global = reference?.variable as? gg.sona.msl.hir.GlobalVariable
            if (global == null || !global.isFunctionConstant) {
                return error(location, "is_function_constant_defined requires a function constant")
            }
            return gg.sona.msl.hir.HIntrinsic(gg.sona.msl.ir.Intrinsic.IsFunctionConstantDefined, analyzed, ScalarType.Bool, location)
        }
        if (sema.atomics.isAtomicFunction(builtinName)) {
            return sema.atomics.resolve(builtinName, analyzed, location) ?: errorExpression(location)
        }
        if (!BuiltinLibrary.contains(builtinName)) return error(location, "use of undeclared identifier '$name'")
        return sema.builtins.resolve(builtinName, analyzed, location)
            ?: error(
                location,
                "no matching function for call to '$builtinName' with arguments (${analyzed.joinToString { it.type.toString() }})",
            )
    }

    private fun builtinName(name: QualifiedName): String? {
        val segments = name.segments.filter { it != "metal" }
        return when {
            segments.size == 1 -> segments[0]
            segments.size == 2 && (segments[0] == "fast" || segments[0] == "precise") -> segments[1]
            else -> null
        }
    }

    private fun methodCall(callee: MemberExpr, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        var base = analyze(callee.base, scope)
        if (isError(base)) return base
        if (callee.isArrow) {
            if (base.type !is PointerType) return error(location, "member reference type '${base.type}' is not a pointer")
            base = HDeref(base, location)
        }
        return when (val type = ConversionRules.valueType(base.type)) {
            is TextureType -> {
                val analyzed = arguments.map { analyze(it, scope) }
                if (analyzed.any { isError(it) }) return errorExpression(location)
                sema.textures.resolve(base, callee.member, analyzed, location) ?: errorExpression(location)
            }

            is StructType -> {
                val methods = sema.methodSets[type] ?: return error(location, "'$type' has no member functions")
                sema.calls.resolveMethodCall(base, methods, callee.member, arguments, scope, location)
                    ?: errorExpression(location)
            }

            else -> error(location, "member reference base type '$type' has no member functions")
        }
    }

    private fun member(expression: MemberExpr, scope: Scope): HExpr {
        val location = expression.location
        var base = analyze(expression.base, scope)
        if (isError(base)) return base
        if (expression.isArrow) {
            if (base.type !is PointerType) return error(location, "member reference type '${base.type}' is not a pointer")
            base = HDeref(base, location)
        }
        return when (val type = ConversionRules.valueType(base.type)) {
            is StructType -> {
                val field = type.field(expression.member) ?: return error(location, "no member named '${expression.member}' in '$type'")
                HMember(base, field, location)
            }

            is VectorType -> swizzle(base, type, expression.member, location)
            else -> error(location, "member reference base type '$type' is not a structure or vector")
        }
    }

    private fun swizzle(base: HExpr, type: VectorType, member: String, location: SourceLocation): HExpr {
        if (member.length !in 1..4) return error(location, "invalid vector swizzle '$member'")
        val set = when {
            member.all { it in "xyzw" } -> "xyzw"
            member.all { it in "rgba" } -> "rgba"
            else -> return error(location, "invalid vector swizzle '$member'")
        }
        val components = IntArray(member.length) { set.indexOf(member[it]) }
        if (components.any { it >= type.size }) return error(location, "vector component access exceeds type '$type'")
        val resultType: Type = if (components.size == 1) type.element else VectorType.of(type.element, components.size)
        return HSwizzle(base, components, resultType, location)
    }

    private fun index(expression: IndexExpr, scope: Scope): HExpr {
        val location = expression.location
        val base = analyze(expression.base, scope)
        val index = analyze(expression.index, scope)
        if (isError(base) || isError(index)) return errorExpression(location)
        val indexType = ConversionRules.valueType(index.type)
        val indexValue = when {
            indexType is ScalarType && indexType.kind.isInteger -> index
            indexType is EnumType && !indexType.isScoped -> ConversionRules.convert(index, indexType.underlying)
            else -> return error(location, "array subscript is not an integer")
        }
        return when (val type = ConversionRules.valueType(base.type)) {
            is ArrayType -> {
                val constant = sema.fold(indexValue) as? ScalarConstant
                if (constant != null && !type.isUnsized && (constant.asLong < 0 || constant.asLong >= type.size)) {
                    diagnostics.warning(location, "array index ${constant.asLong} is past the end of the array")
                }
                HIndex(base, indexValue, type.element, location)
            }

            is VectorType -> HIndex(base, indexValue, type.element, location)
            is MatrixType -> HIndex(base, indexValue, type.columnType, location)
            is PointerType -> HDeref(HPointerOffset(base, indexValue, location), location)
            else -> error(location, "subscripted value of type '$type' is not an array, vector, matrix or pointer")
        }
    }

    private fun cast(expression: CastExpr, scope: Scope): HExpr {
        val location = expression.location
        val target = sema.types.resolve(expression.type, scope) ?: return errorExpression(location)
        val operand = analyze(expression.operand, scope, target)
        if (isError(operand)) return operand
        return when (expression.style) {
            CastStyle.AsType -> bitcast(operand, target, location)
            CastStyle.Reinterpret -> {
                val source = ConversionRules.valueType(operand.type)
                if (source is PointerType && target is PointerType && source.addressSpace == target.addressSpace &&
                    ConversionRules.sameValueType(source.pointee, target.pointee)
                ) {
                    HConvert(operand, target, location)
                } else {
                    error(location, "reinterpret_cast from '$source' to '$target' is not supported")
                }
            }

            else -> convertExplicitly(operand, target, location)
        }
    }

    fun convertExplicitly(operand: HExpr, target: Type, location: SourceLocation): HExpr {
        val source = ConversionRules.valueType(operand.type)
        val destination = ConversionRules.valueType(target)
        if (source == destination) return operand
        if (!ConversionRules.explicitlyConvertible(source, destination)) {
            return error(location, "cannot convert '$source' to '$destination'")
        }
        if (source is PointerType && destination is PointerType && !ConversionRules.sameValueType(source.pointee, destination.pointee)) {
            return error(location, "casts between unrelated pointer types are not supported")
        }
        return ConversionRules.convert(operand, destination)
    }

    private fun bitcast(operand: HExpr, target: Type, location: SourceLocation): HExpr {
        val source = ConversionRules.valueType(operand.type)
        val destination = ConversionRules.valueType(target)
        val sourceBits = dataBits(source)
        val destinationBits = dataBits(destination)
        if (sourceBits == null || destinationBits == null || sourceBits != destinationBits) {
            return error(location, "as_type requires types of the same size ('$source' and '$destination')")
        }
        if (source == destination) return operand
        return HBitcast(operand, destination, location)
    }

    private fun dataBits(type: Type): Int? = when (type) {
        is ScalarType -> if (type.kind.isBool) null else type.kind.bits
        is VectorType -> if (type.element.kind.isBool) null else type.element.kind.bits * type.size
        else -> null
    }

    fun construct(target: Type, arguments: List<Expr>, braced: Boolean, scope: Scope, location: SourceLocation): HExpr {
        val type = ConversionRules.valueType(target)
        return when (type) {
            is ScalarType, is EnumType -> when (arguments.size) {
                0 -> HLiteral(ConstantFolder(emptyMap()).zero(type), location)
                1 -> {
                    val value = analyze(arguments[0], scope, type)
                    if (isError(value)) value else if (braced) convertImplicitly(value, type, "initializer") else convertExplicitly(value, type, location)
                }

                else -> error(location, "too many arguments to construct '$type'")
            }

            is VectorType -> vector(type, arguments, scope, location)
            is MatrixType -> matrix(type, arguments, scope, location)
            is StructType -> aggregate(type, arguments, braced, scope, location)
            is ArrayType -> array(type, arguments, scope, location)
            is SampleOptionType -> sampleOption(type, arguments, scope, location)
            is PointerType -> when (arguments.size) {
                0 -> HLiteral(ZeroConstant(NullPointerType), location)
                1 -> convertImplicitly(analyze(arguments[0], scope, type), type, "initializer")
                else -> error(location, "too many arguments to construct '$type'")
            }

            is TextureType, is gg.sona.msl.types.SamplerType, is AtomicType -> {
                if (arguments.size == 1) {
                    val value = analyze(arguments[0], scope)
                    if (ConversionRules.valueType(value.type) == type) return value
                }
                error(location, "cannot construct a value of type '$type'")
            }

            else -> error(location, "cannot construct a value of type '$type'")
        }
    }

    private fun vector(type: VectorType, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        if (arguments.isEmpty()) return HLiteral(ZeroConstant(type), location)
        val values = arguments.map { analyze(it, scope) }
        if (values.any { isError(it) }) return errorExpression(location)
        if (values.size == 1) {
            val only = values[0]
            val source = ConversionRules.valueType(only.type)
            if (source is ScalarType || (source is EnumType && !source.isScoped)) {
                return HConstruct(type, listOf(ConversionRules.convert(only, type.element)), location)
            }
            if (source is VectorType && source.size == type.size) return ConversionRules.convert(only, type)
        }
        var count = 0
        val converted = ArrayList<HExpr>(values.size)
        for (value in values) {
            when (val source = ConversionRules.valueType(value.type)) {
                is ScalarType -> {
                    converted.add(ConversionRules.convert(value, type.element))
                    count++
                }

                is VectorType -> {
                    converted.add(ConversionRules.convert(value, VectorType.of(type.element, source.size)))
                    count += source.size
                }

                else -> return error(value.location, "cannot use a value of type '$source' to construct '$type'")
            }
        }
        if (count != type.size) {
            return error(location, "'$type' constructor requires ${type.size} components, got $count")
        }
        return HConstruct(type, converted, location)
    }

    private fun matrix(type: MatrixType, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        if (arguments.isEmpty()) return HLiteral(ZeroConstant(type), location)
        val values = arguments.map { analyze(it, scope) }
        if (values.any { isError(it) }) return errorExpression(location)
        if (values.size == 1) {
            val source = ConversionRules.valueType(values[0].type)
            if (source is ScalarType) return HConstruct(type, listOf(ConversionRules.convert(values[0], type.element)), location)
            if (source is MatrixType) {
                if (source.columns == type.columns && source.rows == type.rows) return ConversionRules.convert(values[0], type)
                return error(location, "cannot convert '$source' to '$type'")
            }
        }
        val columns = ArrayList<HExpr>()
        val pending = ArrayList<HExpr>()
        var pendingCount = 0
        for (value in values) {
            when (val source = ConversionRules.valueType(value.type)) {
                is ScalarType -> {
                    pending.add(ConversionRules.convert(value, type.element))
                    pendingCount++
                }

                is VectorType -> {
                    if (pendingCount + source.size > type.rows) {
                        return error(value.location, "matrix constructor argument crosses a column boundary")
                    }
                    pending.add(ConversionRules.convert(value, VectorType.of(type.element, source.size)))
                    pendingCount += source.size
                }

                else -> return error(value.location, "cannot use a value of type '$source' to construct '$type'")
            }
            if (pendingCount == type.rows) {
                val column = if (pending.size == 1 && ConversionRules.valueType(pending[0].type) == type.columnType) {
                    pending[0]
                } else {
                    HConstruct(type.columnType, pending.toList(), location)
                }
                columns.add(column)
                pending.clear()
                pendingCount = 0
            }
        }
        if (pendingCount != 0 || columns.size != type.columns) {
            return error(location, "'$type' constructor requires ${type.columns * type.rows} components")
        }
        return HConstruct(type, columns, location)
    }

    private fun aggregate(type: StructType, arguments: List<Expr>, braced: Boolean, scope: Scope, location: SourceLocation): HExpr {
        if (!type.isComplete) return error(location, "incomplete type '$type'")
        if (arguments.size == 1 && arguments[0] !is InitListExpr) {
            val value = analyze(arguments[0], scope, type.fields.firstOrNull()?.type)
            if (ConversionRules.valueType(value.type) == type) return value
            if (type.fields.isEmpty()) return error(location, "too many initializers for '$type'")
            val converted = convertImplicitly(value, type.fields[0].type, "initializer")
            return HConstruct(type, listOf(converted) + type.fields.drop(1).map { zero(it.type, location) }, location)
        }
        if (arguments.size > type.fields.size) return error(location, "too many initializers for '$type'")
        if (type.isUnion && arguments.size > 1) return error(location, "too many initializers for union '$type'")
        val elements = type.fields.mapIndexed { index, field ->
            val argument = arguments.getOrNull(index)
            if (argument == null) zero(field.type, location) else coerceInitializer(argument, field.type, scope)
        }
        if (!braced && arguments.isNotEmpty()) {
            diagnostics.warning(location, "parenthesized aggregate initialization of '$type'")
        }
        return HConstruct(type, elements, location)
    }

    private fun zero(type: Type, location: SourceLocation): HExpr = HLiteral(ConstantFolder(emptyMap()).zero(ConversionRules.valueType(type)), location)

    private fun array(type: ArrayType, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        if (type.isUnsized) return error(location, "cannot construct an array of unknown size")
        if (arguments.size == 1 && arguments[0] !is InitListExpr) {
            val value = analyze(arguments[0], scope, type.element)
            if (ConversionRules.valueType(value.type) == type) return value
        }
        if (arguments.size > type.size) return error(location, "too many initializers for '$type'")
        val elements = List(type.size) { index ->
            val argument = arguments.getOrNull(index)
            if (argument == null) zero(type.element, location) else coerceInitializer(argument, type.element, scope)
        }
        return HConstruct(type, elements, location)
    }

    private fun sampleOption(type: SampleOptionType, arguments: List<Expr>, scope: Scope, location: SourceLocation): HExpr {
        val parameters: List<Type> = when (type.kind) {
            SampleOptionKind.Bias, SampleOptionKind.Level, SampleOptionKind.MinLodClamp -> listOf(ScalarType.Float)
            SampleOptionKind.Gradient2D -> List(2) { VectorType.of(ScalarType.Float, 2) }
            SampleOptionKind.Gradient3D, SampleOptionKind.GradientCube -> List(2) { VectorType.of(ScalarType.Float, 3) }
        }
        if (arguments.size != parameters.size) return error(location, "'${type.kind.spelling}' expects ${parameters.size} arguments")
        val values = arguments.mapIndexed { index, argument ->
            convertImplicitly(analyze(argument, scope), parameters[index], "argument")
        }
        return HConstruct(type, values, location)
    }

    private fun sizeof(expression: SizeofExpr, scope: Scope): HExpr {
        val location = expression.location
        val type = expression.type?.let { sema.types.resolve(it, scope) }
            ?: expression.operand?.let { analyze(it, scope).type }
            ?: return errorExpression(location)
        if (type == ErrorType) return errorExpression(location)
        val value = if (expression.isAlignof) TypeLayout.alignOf(type) else TypeLayout.sizeOf(type)
        return HLiteral(ScalarConstant.of(ScalarType.UInt, value.toLong()), location)
    }

    private companion object {
        val OPERATORS = mapOf(
            BinaryOperator.Add to HBinaryOperator.Add,
            BinaryOperator.Subtract to HBinaryOperator.Subtract,
            BinaryOperator.Multiply to HBinaryOperator.Multiply,
            BinaryOperator.Divide to HBinaryOperator.Divide,
            BinaryOperator.Remainder to HBinaryOperator.Remainder,
            BinaryOperator.ShiftLeft to HBinaryOperator.ShiftLeft,
            BinaryOperator.ShiftRight to HBinaryOperator.ShiftRight,
            BinaryOperator.Less to HBinaryOperator.Less,
            BinaryOperator.Greater to HBinaryOperator.Greater,
            BinaryOperator.LessEqual to HBinaryOperator.LessEqual,
            BinaryOperator.GreaterEqual to HBinaryOperator.GreaterEqual,
            BinaryOperator.Equal to HBinaryOperator.Equal,
            BinaryOperator.NotEqual to HBinaryOperator.NotEqual,
            BinaryOperator.BitwiseAnd to HBinaryOperator.BitwiseAnd,
            BinaryOperator.BitwiseXor to HBinaryOperator.BitwiseXor,
            BinaryOperator.BitwiseOr to HBinaryOperator.BitwiseOr,
        )
    }
}
