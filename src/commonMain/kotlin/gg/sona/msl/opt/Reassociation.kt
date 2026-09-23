package gg.sona.msl.opt

import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.IrBuilder
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrFunction
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.Opcode
import gg.sona.msl.ir.Value
import gg.sona.msl.passes.Uses

object Reassociation {
    private class Term(val value: Value, val negative: Boolean, val coefficient: ConstantScalar? = null)

    fun run(function: IrFunction): Boolean {
        val uses = Uses(function)
        val builder = IrBuilder(function)
        val replacements = HashMap<Value, Value>()
        for (block in function.blocks) {
            for (instruction in block.instructions.toList()) {
                val kind = kind(instruction) ?: continue
                if (uses.of(instruction).singleOrNull()?.let { user -> kind(user) == kind && user.type == instruction.type } == true) continue
                val terms = ArrayList<Term>()
                val interior = flatten(instruction, kind, instruction.type, false, uses, terms, isRoot = true)
                if (interior < 2) continue
                val reduced = reduce(kind, instruction.type, terms, uses) ?: continue
                builder.positionBefore(instruction)
                replacements[instruction] = rebuild(builder, kind, instruction.type, reduced)
            }
        }
        if (replacements.isEmpty()) return false
        IrRewriter.replace(function, replacements)
        return true
    }

    private fun kind(instruction: Instruction): Opcode? = when (instruction.opcode) {
        Opcode.FAdd, Opcode.FSub -> if ((instruction.type.scalar as? IrFloat)?.bits == 64) null else Opcode.FAdd
        Opcode.IAdd, Opcode.ISub -> Opcode.IAdd
        Opcode.FMul -> if ((instruction.type.scalar as? IrFloat)?.bits == 64) null else Opcode.FMul
        Opcode.IMul, Opcode.And, Opcode.Or, Opcode.Xor -> instruction.opcode
        else -> null
    }

    private fun flatten(value: Value, kind: Opcode, type: IrType, negative: Boolean, uses: Uses, terms: MutableList<Term>, isRoot: Boolean): Int {
        val instruction = value as? Instruction
        val expandable = instruction != null && kind(instruction) == kind && instruction.type == type && (isRoot || uses.of(instruction).size == 1)
        if (!expandable) {
            if (kind == Opcode.FAdd && instruction?.opcode == Opcode.FNeg && instruction.type == type && uses.of(instruction).size == 1) {
                terms.add(Term(instruction.operands[0], !negative))
            } else {
                terms.add(Term(value, negative))
            }
            return 0
        }
        val subtract = instruction.opcode == Opcode.FSub || instruction.opcode == Opcode.ISub
        val left = flatten(instruction.operands[0], kind, type, negative, uses, terms, false)
        val right = flatten(instruction.operands[1], kind, type, if (subtract) !negative else negative, uses, terms, false)
        return 1 + left + right
    }

    private fun reduce(kind: Opcode, type: IrType, terms: List<Term>, uses: Uses): List<Term>? {
        val constants = terms.filter { it.value is IrConstant && ConstantFolding.isFoldable(it.value) }
        var remaining = terms.filterNot { it in constants }.toMutableList()
        var changed = constants.size > 1
        when (kind) {
            Opcode.FAdd, Opcode.IAdd -> {
                val grouped = combine(kind, type, remaining, uses)
                if (grouped != null) {
                    remaining = grouped.toMutableList()
                    changed = true
                }
            }

            Opcode.And, Opcode.Or -> {
                val distinct = remaining.distinctBy { it.value }
                if (distinct.size != remaining.size) changed = true
                remaining = distinct.toMutableList()
            }

            Opcode.Xor -> {
                val odd = ArrayList<Term>()
                for (term in remaining) {
                    val twin = odd.indexOfFirst { it.value === term.value }
                    if (twin >= 0) {
                        odd.removeAt(twin)
                        changed = true
                    } else {
                        odd.add(term)
                    }
                }
                remaining = odd
            }
        }
        if (!changed) return null
        var constant: IrConstant? = null
        for (term in constants) {
            val value = if (term.negative) fold(negation(kind), type, listOf(term.value)) ?: return null else term.value as IrConstant
            constant = if (constant == null) value else fold(kind, type, listOf(constant, value)) ?: return null
        }
        val result = remaining.toMutableList()
        if (constant != null && !identity(kind, constant)) result.add(Term(constant, false))
        if (constant != null && absorbing(kind, constant)) return listOf(Term(constant, false))
        return result
    }

    private fun combine(kind: Opcode, type: IrType, terms: List<Term>, uses: Uses): List<Term>? {
        val scalar = type as? IrScalar ?: return null
        val product = if (kind == Opcode.FAdd) Opcode.FMul else Opcode.IMul
        val originals = LinkedHashMap<Value, MutableList<Term>>()
        val factors = HashMap<Term, ConstantScalar?>()
        for (term in terms) {
            var base = term.value
            val instruction = term.value as? Instruction
            if (instruction != null && instruction.opcode == product && instruction.type == type && uses.of(instruction).size == 1) {
                val index = instruction.operands.indexOfFirst { it is ConstantScalar }
                if (index >= 0) {
                    factors[term] = instruction.operands[index] as ConstantScalar
                    base = instruction.operands[1 - index]
                }
            }
            originals.getOrPut(base) { ArrayList() }.add(term)
        }
        if (originals.values.none { it.size > 1 }) return null
        val result = ArrayList<Term>()
        for ((base, group) in originals) {
            if (group.size == 1) {
                result.add(group[0])
                continue
            }
            val sign = { term: Term -> if (term.negative) -1 else 1 }
            val unit: Int
            val coefficient: ConstantScalar
            if (scalar is IrFloat) {
                val sum = group.sumOf { sign(it) * (factors[it]?.asDouble ?: 1.0) }
                unit = if (sum == 0.0) 0 else if (sum == 1.0) 1 else if (sum == -1.0) -1 else 2
                coefficient = ConstantScalar.float(scalar, ConstantFolding.round(scalar, sum))
            } else {
                val integer = scalar as? IrInt ?: return null
                val sum = group.sumOf { sign(it) * (factors[it]?.bits ?: 1L) }
                val normalized = ConstantScalar.int(IrInt.of(integer.bits, true), sum).bits
                unit = if (normalized == 0L) 0 else if (normalized == 1L) 1 else if (normalized == -1L) -1 else 2
                coefficient = ConstantScalar.int(integer, sum)
            }
            when (unit) {
                0 -> Unit
                1 -> result.add(Term(base, false))
                -1 -> result.add(Term(base, true))
                else -> result.add(Term(base, false, coefficient))
            }
        }
        return result
    }

    private fun negation(kind: Opcode): Opcode = if (kind == Opcode.FAdd) Opcode.FNeg else Opcode.INeg

    private fun fold(opcode: Opcode, type: IrType, operands: List<Value>): IrConstant? = ConstantFolding.fold(Instruction(opcode, type, operands))

    private fun identity(kind: Opcode, constant: IrConstant): Boolean = when (kind) {
        Opcode.FAdd, Opcode.IAdd, Opcode.Or, Opcode.Xor -> Constants.isZero(constant)
        Opcode.FMul, Opcode.IMul -> Constants.scalars(constant).all { it == Constants.one(it.type.scalar) }
        Opcode.And -> Constants.scalars(constant).all { fold(Opcode.Not, it.type, listOf(it))?.let(Constants::isZero) == true }
        else -> false
    }

    private fun absorbing(kind: Opcode, constant: IrConstant): Boolean = when (kind) {
        Opcode.FMul, Opcode.IMul, Opcode.And -> Constants.isZero(constant)
        else -> false
    }

    private fun rebuild(builder: IrBuilder, kind: Opcode, type: IrType, terms: List<Term>): Value {
        if (terms.isEmpty()) return identityValue(kind, type)
        val positive = terms.filterNot { it.negative }
        val negative = terms.filter { it.negative }
        val subtract = if (kind == Opcode.FAdd) Opcode.FSub else Opcode.ISub
        val product = if (kind == Opcode.FAdd) Opcode.FMul else Opcode.IMul
        fun value(term: Term): Value = term.coefficient?.let { builder.binary(product, type, term.value, it) } ?: term.value
        if (positive.isEmpty()) {
            val sum = negative.drop(1).fold(value(negative[0])) { acc, term -> builder.binary(kind, type, acc, value(term)) }
            return builder.unary(negation(kind), type, sum)
        }
        var result = value(positive[0])
        for (term in positive.drop(1)) result = builder.binary(kind, type, result, value(term))
        for (term in negative) result = builder.binary(subtract, type, result, value(term))
        return result
    }

    private fun identityValue(kind: Opcode, type: IrType): Value = when (kind) {
        Opcode.FMul, Opcode.IMul -> Constants.splat(type, Constants.one(type.scalar))
        Opcode.And -> fold(Opcode.Not, type, listOf(Constants.zero(type)))!!
        else -> Constants.zero(type)
    }
}
