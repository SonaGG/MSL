package gg.sona.msl.lower

import gg.sona.msl.hir.Function
import gg.sona.msl.hir.GlobalVariable
import gg.sona.msl.hir.HAddressOf
import gg.sona.msl.hir.HAssign
import gg.sona.msl.hir.HAtomic
import gg.sona.msl.hir.HBinary
import gg.sona.msl.hir.HBinaryOperator
import gg.sona.msl.hir.HBitcast
import gg.sona.msl.hir.HBlock
import gg.sona.msl.hir.HBreak
import gg.sona.msl.hir.HCall
import gg.sona.msl.hir.HConstructorCall
import gg.sona.msl.hir.HComma
import gg.sona.msl.hir.HCompoundAssign
import gg.sona.msl.hir.HConditional
import gg.sona.msl.hir.HConstruct
import gg.sona.msl.hir.HContinue
import gg.sona.msl.hir.HConvert
import gg.sona.msl.hir.HDeclare
import gg.sona.msl.hir.HDeref
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HExprStatement
import gg.sona.msl.hir.HIf
import gg.sona.msl.hir.HIncDec
import gg.sona.msl.hir.HIndex
import gg.sona.msl.hir.HIntrinsic
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.HLogical
import gg.sona.msl.hir.HLoop
import gg.sona.msl.hir.HMember
import gg.sona.msl.hir.HPointerOffset
import gg.sona.msl.hir.HReturn
import gg.sona.msl.hir.HStmt
import gg.sona.msl.hir.HSwitch
import gg.sona.msl.hir.HSwizzle
import gg.sona.msl.hir.HTextureOperation
import gg.sona.msl.hir.HUnary
import gg.sona.msl.hir.HUnaryOperator
import gg.sona.msl.hir.HVariableRef
import gg.sona.msl.hir.LocalVariable
import gg.sona.msl.ir.Block
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.ConstructKind
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructType
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class FunctionLowering(
    val lowering: Lowering,
    val function: IrFunction,
    private val source: Function,
    private val structuredReturns: Boolean,
    private val returnSink: ((Value?) -> Unit)?,
) {
    val builder = IrBuilder(function)
    private val bindings = HashMap<LocalVariable, Binding>()
    private val frames = ArrayList<Frame>()
    private var returnValue: Instruction? = null
    private var returnFlag: Instruction? = null
    private var exitBlock: Block? = null
    private val returnCache = HashMap<HStmt, Boolean>()
    private val intrinsics = IntrinsicLowering(this)
    private val types = lowering.types

    init {
        builder.position(function.newBlock("entry"))
    }

    fun error(location: SourceLocation, message: String) = lowering.diagnostics.error(location, message)

    fun bind(variable: LocalVariable, binding: Binding) {
        bindings[variable] = binding
    }

    fun lowerType(type: Type): IrType = types.lower(type)

    fun lowerBody(body: HBlock) {
        if (structuredReturns) {
            if (source.returnType != VoidType) returnValue = builder.variable(lowerType(source.returnType), "retval")
            returnFlag = builder.variable(IrBool, "returned", ConstantScalar.bool(false))
            exitBlock = builder.newBlock("exit")
            statements(body.statements)
            builder.branch(exitBlock!!)
            builder.position(exitBlock!!)
            builder.ret(returnValue?.let { builder.load(it) })
        } else {
            statements(body.statements)
            if (!builder.isTerminated) {
                returnSink?.invoke(null)
                builder.ret()
            }
        }
    }

    private fun containsReturn(statement: HStmt): Boolean = returnCache.getOrPut(statement) {
        when (statement) {
            is HReturn -> true
            is HBlock -> statement.statements.any { containsReturn(it) }
            is HIf -> containsReturn(statement.thenBranch) || statement.elseBranch?.let { containsReturn(it) } == true
            is HLoop -> containsReturn(statement.body)
            is HSwitch -> statement.cases.any { case -> case.body.any { containsReturn(it) } }
            else -> false
        }
    }

    private fun statements(list: List<HStmt>, from: Int = 0) {
        for (index in from until list.size) {
            val statement = list[index]
            statement(statement)
            if (statement is HReturn) return
            if (structuredReturns && containsReturn(statement)) {
                if (frames.isNotEmpty()) {
                    guardBreak(frames.last().breakTarget)
                } else if (index + 1 < list.size) {
                    val flag = builder.load(returnFlag!!)
                    val notReturned = builder.unary(Opcode.LogicalNot, IrBool, flag)
                    selection(notReturned) { statements(list, index + 1) }
                    return
                }
            }
        }
    }

    private fun guardBreak(target: Block) {
        val flag = builder.load(returnFlag!!)
        val breakBlock = builder.newBlock("return_break")
        val merge = builder.newBlock("return_merge")
        val header = builder.block
        header.construct = ConstructKind.Selection
        header.merge = merge
        builder.condBranch(flag, breakBlock, merge)
        builder.position(breakBlock)
        builder.branch(target)
        builder.position(merge)
    }

    private inline fun selection(condition: Value, body: () -> Unit) {
        val thenBlock = builder.newBlock("then")
        val merge = builder.newBlock("merge")
        val header = builder.block
        header.construct = ConstructKind.Selection
        header.merge = merge
        builder.condBranch(condition, thenBlock, merge)
        builder.position(thenBlock)
        body()
        builder.branch(merge)
        builder.position(merge)
    }

    fun statement(statement: HStmt) {
        when (statement) {
            is HBlock -> statements(statement.statements)
            is HDeclare -> declare(statement)
            is HExprStatement -> discard(statement.expression)
            is HIf -> ifStatement(statement)
            is HLoop -> loop(statement)
            is HSwitch -> switchStatement(statement)
            is HBreak -> builder.branch(frames.last().breakTarget)
            is HContinue -> builder.branch(frames.last { it.continueTarget != null }.continueTarget!!)
            is HReturn -> returnStatement(statement)
        }
    }

    private fun discard(expression: HExpr) {
        if (expression.type == VoidType) {
            rvalueOrVoid(expression)
        } else {
            rvalue(expression)
        }
    }

    private fun declare(statement: HDeclare) {
        val variable = statement.variable
        val initializer = statement.initializer
        if (variable.isReference) {
            val target = lvalue(initializer!!)
            bindings[variable] = Binding(address(target, initializer.location), false)
            return
        }
        if (variable.addressSpace == AddressSpace.Threadgroup) {
            bindings[variable] = Binding(lowering.threadgroupVariable(variable), false)
            return
        }
        if (isOpaque(variable.type)) {
            if (initializer == null) {
                error(statement.location, "opaque variable '${variable.name}' requires an initializer")
                return
            }
            bindings[variable] = Binding(rvalue(initializer), true)
            return
        }
        val pointer = builder.variable(lowerType(variable.type), variable.name)
        bindings[variable] = Binding(pointer, false)
        if (initializer != null) builder.store(pointer, rvalue(initializer))
    }

    private fun isOpaque(type: Type): Boolean = type is TextureType || type is SamplerType ||
        (type is ArrayType && (type.element is TextureType || type.element is SamplerType))

    private fun ifStatement(statement: HIf) {
        val condition = rvalue(statement.condition)
        if (condition is ConstantScalar) {
            if (condition.asBoolean) statement(statement.thenBranch) else statement.elseBranch?.let { statement(it) }
            return
        }
        val thenBlock = builder.newBlock("then")
        val elseBlock = statement.elseBranch?.let { builder.newBlock("else") }
        val merge = builder.newBlock("merge")
        val header = builder.block
        header.construct = ConstructKind.Selection
        header.merge = merge
        builder.condBranch(condition, thenBlock, elseBlock ?: merge)
        builder.position(thenBlock)
        statement(statement.thenBranch)
        builder.branch(merge)
        if (elseBlock != null) {
            builder.position(elseBlock)
            statement(statement.elseBranch!!)
            builder.branch(merge)
        }
        builder.position(merge)
    }

    private fun loop(statement: HLoop) {
        val header = builder.newBlock("loop")
        val body = builder.newBlock("body")
        val continueBlock = builder.newBlock("continue")
        val merge = builder.newBlock("loop_merge")
        builder.branch(header)
        builder.position(header)
        header.construct = ConstructKind.Loop
        header.merge = merge
        header.continueTarget = continueBlock
        val condition = statement.condition
        if (statement.conditionFirst && condition != null) {
            val check = builder.newBlock("cond")
            builder.branch(check)
            builder.position(check)
            val value = rvalue(condition)
            builder.condBranch(value, body, merge)
        } else {
            builder.branch(body)
        }
        builder.position(body)
        frames.add(Frame(merge, continueBlock))
        statement(statement.body)
        frames.removeAt(frames.size - 1)
        builder.branch(continueBlock)
        builder.position(continueBlock)
        statement.increment?.let { discard(it) }
        if (!statement.conditionFirst && condition != null) {
            val value = rvalue(condition)
            builder.condBranch(value, header, merge)
        } else {
            builder.branch(header)
        }
        builder.position(merge)
    }

    private fun switchStatement(statement: HSwitch) {
        val selector = rvalue(statement.selector)
        val merge = builder.newBlock("switch_merge")
        val blocks = statement.cases.map { builder.newBlock(if (it.isDefault) "default" else "case") }
        val defaultIndex = statement.cases.indexOfFirst { it.isDefault }
        val header = builder.block
        header.construct = ConstructKind.Selection
        header.merge = merge
        val targets = ArrayList<Pair<Int, Block>>()
        statement.cases.forEachIndexed { index, case -> case.values.forEach { targets.add(it.toInt() to blocks[index]) } }
        builder.switch(selector, if (defaultIndex >= 0) blocks[defaultIndex] else merge, targets)
        frames.add(Frame(merge, null))
        statement.cases.forEachIndexed { index, case ->
            builder.position(blocks[index])
            statements(case.body)
            builder.branch(blocks.getOrNull(index + 1) ?: merge)
        }
        frames.removeAt(frames.size - 1)
        builder.position(merge)
    }

    private fun returnStatement(statement: HReturn) {
        val value = statement.value?.let { rvalue(it) }
        if (!structuredReturns) {
            returnSink?.invoke(value)
            builder.ret()
            return
        }
        if (value != null) builder.store(returnValue!!, value)
        builder.store(returnFlag!!, ConstantScalar.bool(true))
        if (frames.isNotEmpty()) {
            builder.branch(frames.last().breakTarget)
        } else if (isTopLevel()) {
            builder.branch(exitBlock!!)
        }
    }

    private fun isTopLevel(): Boolean = false

    fun rvalueOrVoid(expression: HExpr): Value? = when (expression) {
        is HCall -> call(expression)
        is HConstructorCall -> constructorCall(expression)
        is HIntrinsic -> intrinsics.intrinsic(expression)
        is HTextureOperation -> intrinsics.texture(expression)
        is HAtomic -> intrinsics.atomic(expression)
        is HComma -> {
            rvalueOrVoid(expression.left)
            rvalueOrVoid(expression.right)
        }

        is HConditional -> if (expression.type == VoidType) {
            val condition = rvalue(expression.condition)
            val thenBlock = builder.newBlock("then")
            val elseBlock = builder.newBlock("else")
            val merge = builder.newBlock("merge")
            builder.block.construct = ConstructKind.Selection
            builder.block.merge = merge
            builder.condBranch(condition, thenBlock, elseBlock)
            builder.position(thenBlock)
            rvalueOrVoid(expression.whenTrue)
            builder.branch(merge)
            builder.position(elseBlock)
            rvalueOrVoid(expression.whenFalse)
            builder.branch(merge)
            builder.position(merge)
            null
        } else {
            rvalue(expression)
        }

        else -> rvalue(expression)
    }

    fun rvalue(expression: HExpr): Value {
        if (expression is HUnary || expression is HBinary || expression is HConvert || expression is HConstruct ||
            expression is HSwizzle || expression is HConditional
        ) {
            val type = expression.type
            if (type is ScalarType || type is VectorType || type is MatrixType) {
                folder.fold(expression)?.let { return types.constant(it) }
            }
        }
        return lower(expression)
    }

    private val folder = gg.sona.msl.sema.ConstantFolder(emptyMap())

    private fun lower(expression: HExpr): Value = when (expression) {
        is HLiteral -> types.constant(expression.value)
        is HVariableRef -> variableValue(expression)
        is HUnary -> unary(expression)
        is HBinary -> binary(expression.operator, rvalue(expression.left), rvalue(expression.right), expression.left.type, expression.type)
        is HLogical -> logical(expression)
        is HConditional -> conditional(expression)
        is HAssign -> {
            val target = lvalue(expression.target)
            val value = rvalue(expression.value)
            store(target, value)
            value
        }

        is HCompoundAssign -> compoundAssign(expression)
        is HIncDec -> incDec(expression)
        is HConvert -> convertExpression(expression)
        is HBitcast -> builder.unary(Opcode.Bitcast, lowerType(expression.type), rvalue(expression.operand))
        is HConstruct -> construct(expression)
        is HSwizzle -> {
            val base = rvalue(expression.base)
            builder.shuffle(base, base, expression.components)
        }

        is HMember -> member(expression)
        is HIndex -> index(expression)
        is HDeref -> builder.load(rvalue(expression.pointer))
        is HAddressOf -> address(lvalue(expression.operand), expression.location)
        is HPointerOffset -> builder.ptrOffset(rvalue(expression.pointer), integerIndex(rvalue(expression.offset)))
        is HCall -> call(expression) ?: run {
            error(expression.location, "void value used in expression")
            types.zero(lowerType(expression.type))
        }

        is HConstructorCall -> constructorCall(expression)

        is HIntrinsic -> intrinsics.intrinsic(expression) ?: types.zero(lowerType(expression.type))
        is HTextureOperation -> intrinsics.texture(expression) ?: types.zero(lowerType(expression.type))
        is HAtomic -> intrinsics.atomic(expression) ?: types.zero(lowerType(expression.type))
        is HComma -> {
            rvalueOrVoid(expression.left)
            rvalue(expression.right)
        }
    }

    private fun variableValue(expression: HVariableRef): Value {
        when (val variable = expression.variable) {
            is LocalVariable -> {
                val binding = bindings[variable] ?: run {
                    error(expression.location, "internal error: unbound variable '${variable.name}'")
                    return types.zero(lowerType(variable.type))
                }
                return if (binding.isDirect) binding.value else builder.load(binding.value)
            }

            is GlobalVariable -> {
                val value = lowering.globalValue(variable, expression.location)
                return if (value is gg.sona.msl.ir.GlobalVariable) builder.load(value) else value
            }
        }
    }

    fun lvalue(expression: HExpr): LValue = when (expression) {
        is HVariableRef -> when (val variable = expression.variable) {
            is LocalVariable -> {
                val binding = bindings[variable]
                if (binding == null || binding.isDirect) {
                    error(expression.location, "'${variable.name}' is not addressable")
                    MemoryLValue(builder.variable(lowerType(variable.type)))
                } else {
                    MemoryLValue(binding.value)
                }
            }

            is GlobalVariable -> MemoryLValue(lowering.globalPointer(variable, expression.location))
        }

        is HMember -> {
            val base = memoryBase(expression.base)
            val index = ConstantScalar.i32(expression.member.index)
            MemoryLValue(builder.accessChain(base.pointer, listOf(index)))
        }

        is HIndex -> {
            val baseType = expression.base.type
            val base = memoryBase(expression.base)
            val index = integerIndex(rvalue(expression.index))
            if (baseType is VectorType) ElementLValue(base, index) else MemoryLValue(builder.accessChain(base.pointer, listOf(index)))
        }

        is HSwizzle -> SwizzleLValue(memoryBase(expression.base), expression.components)
        is HDeref -> MemoryLValue(rvalue(expression.pointer))
        else -> {
            val value = rvalue(expression)
            val temporary = builder.variable(value.type)
            builder.store(temporary, value)
            MemoryLValue(temporary)
        }
    }

    private fun memoryBase(expression: HExpr): MemoryLValue {
        if (!expression.isLvalue) {
            val value = rvalue(expression)
            val temporary = builder.variable(value.type)
            builder.store(temporary, value)
            return MemoryLValue(temporary)
        }
        return when (val target = lvalue(expression)) {
            is MemoryLValue -> target
            else -> {
                val value = load(target)
                val temporary = builder.variable(value.type)
                builder.store(temporary, value)
                MemoryLValue(temporary)
            }
        }
    }

    fun address(target: LValue, location: SourceLocation): Value = when (target) {
        is MemoryLValue -> target.pointer
        is ElementLValue -> builder.accessChain(target.base.pointer, listOf(target.index))
        is SwizzleLValue -> {
            if (target.lanes.size == 1) {
                builder.accessChain(target.base.pointer, listOf(ConstantScalar.i32(target.lanes[0])))
            } else {
                error(location, "cannot take the address of a vector swizzle")
                target.base.pointer
            }
        }
    }

    fun load(target: LValue): Value = when (target) {
        is MemoryLValue -> builder.load(target.pointer)
        is SwizzleLValue -> {
            val base = builder.load(target.base.pointer)
            builder.shuffle(base, base, target.lanes)
        }

        is ElementLValue -> {
            val base = builder.load(target.base.pointer)
            extractElement(base, target.index)
        }
    }

    fun store(target: LValue, value: Value) {
        when (target) {
            is MemoryLValue -> builder.store(target.pointer, value)
            is SwizzleLValue -> {
                val old = builder.load(target.base.pointer)
                val count = old.type.componentCount
                val lanes = IntArray(count) { it }
                if (target.lanes.size == 1) {
                    builder.store(target.base.pointer, builder.insert(old, value, target.lanes[0]))
                    return
                }
                target.lanes.forEachIndexed { index, lane -> lanes[lane] = count + index }
                builder.store(target.base.pointer, builder.shuffle(old, value, lanes))
            }

            is ElementLValue -> {
                val old = builder.load(target.base.pointer)
                val index = target.index
                val updated = if (index is ConstantScalar) {
                    builder.insert(old, value, index.bits.toInt())
                } else {
                    builder.emit(Opcode.VectorInsertDynamic, old.type, listOf(old, value, index))
                }
                builder.store(target.base.pointer, updated)
            }
        }
    }

    fun extractElement(vector: Value, index: Value): Value =
        if (index is ConstantScalar) {
            builder.extract(vector, index.bits.toInt())
        } else {
            builder.emit(Opcode.VectorExtractDynamic, vector.type.scalar, listOf(vector, index))
        }

    fun integerIndex(value: Value): Value {
        val type = value.type
        if (type is IrInt && type.bits == 32) return value
        return convert(value, IrInt.I32)
    }

    private fun member(expression: HMember): Value {
        val base = expression.base
        if (base.isLvalue && !isOpaque(base.type)) {
            val pointer = memoryBase(base).pointer
            return builder.load(builder.accessChain(pointer, listOf(ConstantScalar.i32(expression.member.index))))
        }
        return builder.extract(rvalue(base), expression.member.index)
    }

    private fun index(expression: HIndex): Value {
        val baseType = expression.base.type
        val index = rvalue(expression.index)
        if (baseType is VectorType) {
            val vector = rvalue(expression.base)
            return extractElement(vector, integerIndex(index))
        }
        if (index is ConstantScalar && !expression.base.isLvalue) {
            return builder.extract(rvalue(expression.base), index.bits.toInt())
        }
        if (expression.base.isLvalue && isOpaque(baseType)) {
            val base = lvalue(expression.base) as MemoryLValue
            return builder.load(builder.accessChain(base.pointer, listOf(integerIndex(index))))
        }
        val base = memoryBase(expression.base)
        return builder.load(builder.accessChain(base.pointer, listOf(integerIndex(index))))
    }

    private fun unary(expression: HUnary): Value {
        val operand = rvalue(expression.operand)
        val type = lowerType(expression.type)
        return when (expression.operator) {
            HUnaryOperator.Negate -> when {
                type is IrMatrix -> mapColumns(operand, type) { builder.unary(Opcode.FNeg, it.type, it) }
                type.isFloat -> builder.unary(Opcode.FNeg, type, operand)
                else -> builder.unary(Opcode.INeg, type, operand)
            }

            HUnaryOperator.LogicalNot -> builder.unary(Opcode.LogicalNot, type, operand)
            HUnaryOperator.BitwiseNot -> if (type.isBool) {
                builder.unary(Opcode.LogicalNot, type, operand)
            } else {
                builder.unary(Opcode.Not, type, operand)
            }
        }
    }

    private inline fun mapColumns(matrix: Value, type: IrMatrix, transform: (Value) -> Value): Value {
        val columns = List(type.columns) { transform(builder.extract(matrix, it)) }
        return builder.construct(type, columns)
    }

    fun binary(operator: HBinaryOperator, left: Value, right: Value, operandType: Type, resultType: Type): Value {
        val result = lowerType(resultType)
        val leftType = left.type
        val rightType = right.type
        if (leftType is IrMatrix || rightType is IrMatrix) return matrixBinary(operator, left, right, result)
        if (leftType is IrPointer && rightType is IrPointer) {
            val difference = builder.ptrDiff(left, right)
            val zero = ConstantScalar.i32(0)
            return when (operator) {
                HBinaryOperator.Equal -> builder.binary(Opcode.IEqual, result, difference, zero)
                HBinaryOperator.NotEqual -> builder.binary(Opcode.INotEqual, result, difference, zero)
                HBinaryOperator.Less -> builder.binary(Opcode.SLess, result, difference, zero)
                HBinaryOperator.LessEqual -> builder.binary(Opcode.SLessEqual, result, difference, zero)
                HBinaryOperator.Greater -> builder.binary(Opcode.SGreater, result, difference, zero)
                HBinaryOperator.GreaterEqual -> builder.binary(Opcode.SGreaterEqual, result, difference, zero)
                else -> builder.unary(Opcode.SConvert, result, difference)
            }
        }
        val element = leftType.scalar
        if (element is IrBool) {
            val opcode = when (operator) {
                HBinaryOperator.BitwiseAnd -> Opcode.LogicalAnd
                HBinaryOperator.BitwiseOr -> Opcode.LogicalOr
                HBinaryOperator.BitwiseXor, HBinaryOperator.NotEqual -> Opcode.LogicalNotEqual
                HBinaryOperator.Equal -> Opcode.LogicalEqual
                else -> error("invalid boolean operator $operator")
            }
            return builder.binary(opcode, result, left, right)
        }
        val isFloat = element is IrFloat
        val signed = element is IrInt && element.signed
        val opcode = when (operator) {
            HBinaryOperator.Add -> if (isFloat) Opcode.FAdd else Opcode.IAdd
            HBinaryOperator.Subtract -> if (isFloat) Opcode.FSub else Opcode.ISub
            HBinaryOperator.Multiply -> if (isFloat) Opcode.FMul else Opcode.IMul
            HBinaryOperator.Divide -> if (isFloat) Opcode.FDiv else if (signed) Opcode.SDiv else Opcode.UDiv
            HBinaryOperator.Remainder -> if (isFloat) Opcode.FRem else if (signed) Opcode.SRem else Opcode.URem
            HBinaryOperator.ShiftLeft -> Opcode.Shl
            HBinaryOperator.ShiftRight -> if (signed) Opcode.AShr else Opcode.LShr
            HBinaryOperator.BitwiseAnd -> Opcode.And
            HBinaryOperator.BitwiseOr -> Opcode.Or
            HBinaryOperator.BitwiseXor -> Opcode.Xor
            HBinaryOperator.Equal -> if (isFloat) Opcode.FEqual else Opcode.IEqual
            HBinaryOperator.NotEqual -> if (isFloat) Opcode.FNotEqual else Opcode.INotEqual
            HBinaryOperator.Less -> if (isFloat) Opcode.FLess else if (signed) Opcode.SLess else Opcode.ULess
            HBinaryOperator.LessEqual -> if (isFloat) Opcode.FLessEqual else if (signed) Opcode.SLessEqual else Opcode.ULessEqual
            HBinaryOperator.Greater -> if (isFloat) Opcode.FGreater else if (signed) Opcode.SGreater else Opcode.UGreater
            HBinaryOperator.GreaterEqual -> if (isFloat) Opcode.FGreaterEqual else if (signed) Opcode.SGreaterEqual else Opcode.UGreaterEqual
        }
        return builder.binary(opcode, result, left, right)
    }

    private fun matrixBinary(operator: HBinaryOperator, left: Value, right: Value, result: IrType): Value {
        val leftType = left.type
        val rightType = right.type
        return when (operator) {
            HBinaryOperator.Multiply -> when {
                leftType is IrMatrix && rightType is IrMatrix -> builder.binary(Opcode.MatrixTimesMatrix, result, left, right)
                leftType is IrMatrix && rightType is IrVector -> builder.binary(Opcode.MatrixTimesVector, result, left, right)
                leftType is IrVector && rightType is IrMatrix -> builder.binary(Opcode.VectorTimesMatrix, result, left, right)
                leftType is IrMatrix -> builder.binary(Opcode.MatrixTimesScalar, result, left, right)
                else -> builder.binary(Opcode.MatrixTimesScalar, result, right, left)
            }

            HBinaryOperator.Add, HBinaryOperator.Subtract -> {
                val matrix = leftType as IrMatrix
                val opcode = if (operator == HBinaryOperator.Add) Opcode.FAdd else Opcode.FSub
                val columns = List(matrix.columns) {
                    builder.binary(opcode, matrix.column, builder.extract(left, it), builder.extract(right, it))
                }
                builder.construct(matrix, columns)
            }

            HBinaryOperator.Divide -> {
                val matrix = leftType as IrMatrix
                val scalar = matrix.column.element as IrFloat
                val reciprocal = builder.binary(Opcode.FDiv, scalar, Constants.one(scalar), right)
                builder.binary(Opcode.MatrixTimesScalar, result, left, reciprocal)
            }

            else -> error("invalid matrix operator $operator")
        }
    }

    private fun isPure(expression: HExpr): Boolean = when (expression) {
        is HLiteral, is HVariableRef -> true
        is HUnary -> isPure(expression.operand)
        is HBinary -> isPure(expression.left) && isPure(expression.right) &&
            expression.operator != HBinaryOperator.Divide && expression.operator != HBinaryOperator.Remainder

        is HLogical -> isPure(expression.left) && isPure(expression.right)
        is HConvert -> isPure(expression.operand)
        is HBitcast -> isPure(expression.operand)
        is HSwizzle -> isPure(expression.base)
        is HMember -> isPure(expression.base)
        is HIndex -> expression.base.type is VectorType && isPure(expression.base) && isPure(expression.index)
        is HConstruct -> expression.arguments.all { isPure(it) }
        is HConditional -> isPure(expression.condition) && isPure(expression.whenTrue) && isPure(expression.whenFalse)
        is HIntrinsic -> expression.intrinsic.isPure && expression.arguments.all { isPure(it) }
        else -> false
    }

    private fun logical(expression: HLogical): Value {
        val left = rvalue(expression.left)
        if (isPure(expression.right)) {
            val right = rvalue(expression.right)
            return builder.binary(if (expression.isAnd) Opcode.LogicalAnd else Opcode.LogicalOr, IrBool, left, right)
        }
        val header = builder.block
        val rightBlock = builder.newBlock("logical_rhs")
        val merge = builder.newBlock("logical_merge")
        header.construct = ConstructKind.Selection
        header.merge = merge
        if (expression.isAnd) builder.condBranch(left, rightBlock, merge) else builder.condBranch(left, merge, rightBlock)
        builder.position(rightBlock)
        val right = rvalue(expression.right)
        val rightEnd = builder.block
        builder.branch(merge)
        builder.position(merge)
        return builder.phi(IrBool, listOf(ConstantScalar.bool(!expression.isAnd) to header, right to rightEnd))
    }

    private fun conditional(expression: HConditional): Value {
        val type = lowerType(expression.type)
        val condition = rvalue(expression.condition)
        if (condition.type is IrVector || (isPure(expression.whenTrue) && isPure(expression.whenFalse) && type !is IrPointer)) {
            val whenTrue = rvalue(expression.whenTrue)
            val whenFalse = rvalue(expression.whenFalse)
            if (condition is ConstantScalar) return if (condition.asBoolean) whenTrue else whenFalse
            return builder.select(condition, whenTrue, whenFalse)
        }
        if (condition is ConstantScalar) return rvalue(if (condition.asBoolean) expression.whenTrue else expression.whenFalse)
        val header = builder.block
        val thenBlock = builder.newBlock("select_true")
        val elseBlock = builder.newBlock("select_false")
        val merge = builder.newBlock("select_merge")
        header.construct = ConstructKind.Selection
        header.merge = merge
        builder.condBranch(condition, thenBlock, elseBlock)
        builder.position(thenBlock)
        val whenTrue = rvalue(expression.whenTrue)
        val trueEnd = builder.block
        builder.branch(merge)
        builder.position(elseBlock)
        val whenFalse = rvalue(expression.whenFalse)
        val falseEnd = builder.block
        builder.branch(merge)
        builder.position(merge)
        return builder.phi(type, listOf(whenTrue to trueEnd, whenFalse to falseEnd))
    }

    private fun compoundAssign(expression: HCompoundAssign): Value {
        val target = lvalue(expression.target)
        val old = load(target)
        val value = rvalue(expression.value)
        val targetType = expression.target.type
        val operationType = expression.operationType
        val result = if (operationType is PointerType) {
            builder.ptrOffset(old, integerIndex(value))
        } else {
            val left = convert(old, targetType, operationType)
            val combined = binary(expression.operator, left, value, operationType, operationType)
            convert(combined, operationType, targetType)
        }
        store(target, result)
        return result
    }

    private fun incDec(expression: HIncDec): Value {
        val target = lvalue(expression.target)
        val old = load(target)
        val type = old.type
        val updated = if (expression.target.type is PointerType) {
            builder.ptrOffset(old, ConstantScalar.i32(if (expression.isIncrement) 1 else -1))
        } else {
            val one = Constants.splat(type, Constants.one(type.scalar))
            val opcode = when {
                type.isFloat -> if (expression.isIncrement) Opcode.FAdd else Opcode.FSub
                else -> if (expression.isIncrement) Opcode.IAdd else Opcode.ISub
            }
            builder.binary(opcode, type, old, one)
        }
        store(target, updated)
        return if (expression.isPrefix) updated else old
    }

    private fun convertExpression(expression: HConvert): Value {
        val sourceType = expression.operand.type
        val targetType = expression.type
        if (targetType is PointerType && sourceType is ArrayType) {
            val base = lvalue(expression.operand)
            return builder.accessChain(address(base, expression.location), listOf(ConstantScalar.i32(0)))
        }
        if (targetType is PointerType) return rvalue(expression.operand)
        return convert(rvalue(expression.operand), sourceType, targetType)
    }

    fun convert(value: Value, from: Type, to: Type): Value {
        val target = lowerType(to)
        if (value.type == target) return value
        if (to is MatrixType) {
            val matrix = target as IrMatrix
            val sourceMatrix = value.type as IrMatrix
            return builder.construct(matrix, List(matrix.columns) { convert(builder.extract(value, it), matrix.column) }.also {
                require(sourceMatrix.columns == matrix.columns)
            })
        }
        if (from is ScalarType || from is EnumType) {
            if (target is IrVector) return builder.splat(target, convert(value, target.element))
        }
        return convert(value, target)
    }

    fun convert(value: Value, target: IrType): Value {
        val source = value.type
        if (source == target) return value
        if (value is ConstantScalar && target is IrScalar) return constantConvert(value, target)
        val from = source.scalar
        val to = target.scalar
        return when {
            to is IrBool -> when (from) {
                is IrFloat -> builder.binary(Opcode.FNotEqual, target, value, zeroLike(source))
                else -> builder.binary(Opcode.INotEqual, target, value, zeroLike(source))
            }

            from is IrBool -> builder.select(value, oneLike(target), zeroLike(target))
            from is IrFloat && to is IrFloat -> builder.unary(Opcode.FConvert, target, value)
            from is IrFloat && to is IrInt -> builder.unary(if (to.signed) Opcode.FToS else Opcode.FToU, target, value)
            from is IrInt && to is IrFloat -> builder.unary(if (from.signed) Opcode.SToF else Opcode.UToF, target, value)
            from is IrInt && to is IrInt -> {
                if (from.bits == to.bits) {
                    builder.unary(Opcode.Bitcast, target, value)
                } else {
                    builder.unary(if (from.signed) Opcode.SConvert else Opcode.UConvert, target, value)
                }
            }

            else -> error("cannot convert $source to $target")
        }
    }

    private fun zeroLike(type: IrType): IrConstant = Constants.splat(type, Constants.zero(type.scalar) as ConstantScalar)

    private fun oneLike(type: IrType): IrConstant = Constants.splat(type, Constants.one(type.scalar))

    private fun constantConvert(value: ConstantScalar, target: IrScalar): IrConstant {
        val source = value.type
        return when {
            target is IrBool -> ConstantScalar.bool(if (source is IrFloat) value.asDouble != 0.0 else value.bits != 0L)
            source is IrFloat -> Constants.scalar(target, value.asDouble)
            source is IrInt && target is IrFloat -> {
                val numeric = if (source.signed || source.bits < 64) value.bits.toDouble() else value.bits.toULong().toDouble()
                Constants.scalar(target, numeric)
            }

            else -> Constants.integer(target, value.bits)
        }
    }

    private fun construct(expression: HConstruct): Value {
        val type = lowerType(expression.type)
        val arguments = expression.arguments.map { rvalue(it) }
        return when {
            type is IrVector && arguments.size == 1 && arguments[0].type is IrScalar -> builder.splat(type, arguments[0])
            type is IrMatrix && arguments.size == 1 && arguments[0].type is IrScalar -> {
                val scalar = arguments[0]
                val zero = Constants.zero(type.column.element)
                val columns = List(type.columns) { column ->
                    builder.construct(type.column, List(type.rows) { row -> if (row == column) scalar else zero })
                }
                builder.construct(type, columns)
            }

            arguments.isEmpty() -> types.zero(type)
            else -> builder.construct(type, arguments)
        }
    }

    private fun call(expression: HCall): Value? {
        val callee = lowering.functionFor(expression.function, expression.location) ?: return null
        val parameters = expression.function.parameters
        val arguments = expression.arguments.mapIndexed { index, argument -> argument(parameters[index], argument) }
        val result = builder.call(callee, arguments)
        return if (expression.function.returnType == VoidType) null else result
    }

    private fun constructorCall(expression: HConstructorCall): Value {
        val type = lowerType(expression.struct)
        val callee = lowering.functionFor(expression.constructor, expression.location) ?: return types.zero(type)
        val parameters = expression.constructor.parameters
        val temporary = builder.variable(type)
        val arguments = expression.arguments.mapIndexed { index, argument -> argument(parameters[index + 1], argument) }
        builder.call(callee, listOf(temporary) + arguments)
        return builder.load(temporary)
    }

    private fun argument(parameter: LocalVariable, argument: HExpr): Value = when {
        parameter.isReference -> {
            if (argument.isLvalue && argument.lvalueAddressSpace == parameter.addressSpace) {
                address(lvalue(argument), argument.location)
            } else {
                val value = rvalue(argument)
                val temporary = builder.variable(value.type)
                builder.store(temporary, value)
                temporary
            }
        }

        else -> rvalue(argument)
    }

    fun isVoid(type: Type): Boolean = type == VoidType

    fun scalarKindOf(type: Type): ScalarKind? = when (type) {
        is ScalarType -> type.kind
        is VectorType -> type.element.kind
        is MatrixType -> type.element.kind
        is EnumType -> type.underlying.kind
        is StructType -> null
        else -> null
    }
}
