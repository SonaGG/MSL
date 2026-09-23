package gg.sona.msl.lower

import gg.sona.msl.ast.Attribute
import gg.sona.msl.ast.BinaryExpr
import gg.sona.msl.ast.BinaryOperator
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.IntLiteralExpr
import gg.sona.msl.ast.NameExpr
import gg.sona.msl.ast.UnaryExpr
import gg.sona.msl.ast.UnaryOperator
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.Program
import gg.sona.msl.hir.ScalarConstant

class AttributeValues(private val program: Program) {
    fun find(attributes: List<Attribute>, name: String): Attribute? =
        attributes.firstOrNull { it.name == name && (it.namespace == null || it.namespace == "metal") }

    fun has(attributes: List<Attribute>, name: String): Boolean = find(attributes, name) != null

    fun integer(attribute: Attribute, index: Int = 0): Int? = attribute.arguments.getOrNull(index)?.let { evaluate(it) }?.toInt()

    fun identifier(attribute: Attribute, index: Int = 0): String? =
        (attribute.arguments.getOrNull(index) as? NameExpr)?.name?.last

    private fun evaluate(expression: Expr): Long? = when (expression) {
        is IntLiteralExpr -> expression.value
        is UnaryExpr -> when (expression.operator) {
            UnaryOperator.Minus -> evaluate(expression.operand)?.let { -it }
            UnaryOperator.Plus -> evaluate(expression.operand)
            else -> null
        }

        is BinaryExpr -> {
            val left = evaluate(expression.left)
            val right = evaluate(expression.right)
            if (left == null || right == null) {
                null
            } else {
                when (expression.operator) {
                    BinaryOperator.Add -> left + right
                    BinaryOperator.Subtract -> left - right
                    BinaryOperator.Multiply -> left * right
                    BinaryOperator.Divide -> if (right == 0L) null else left / right
                    BinaryOperator.ShiftLeft -> left shl right.toInt()
                    BinaryOperator.BitwiseOr -> left or right
                    else -> null
                }
            }
        }

        is NameExpr -> {
            val global = program.globals.firstOrNull { it.name == expression.name.last }
            when (val value = global?.constantValue) {
                is ScalarConstant -> value.asLong
                is EnumConstant -> value.value
                else -> null
            }
        }

        else -> null
    }
}
