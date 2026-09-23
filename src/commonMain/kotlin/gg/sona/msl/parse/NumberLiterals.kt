package gg.sona.msl.parse

import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.FloatLiteralExpr
import gg.sona.msl.ast.FloatSuffix
import gg.sona.msl.ast.IntLiteralExpr
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import kotlin.math.pow

object NumberLiterals {
    fun parse(spelling: String, location: SourceLocation, diagnostics: Diagnostics): Expr {
        val text = spelling.replace("'", "")
        val lower = text.lowercase()
        val isHex = lower.startsWith("0x")
        val isFloat = if (isHex) 'p' in lower else '.' in lower || 'e' in lower
        return if (isFloat) {
            parseFloat(text, lower, isHex, spelling, location, diagnostics)
        } else {
            parseInteger(lower, spelling, location, diagnostics)
        }
    }

    private fun parseFloat(
        text: String,
        lower: String,
        isHex: Boolean,
        spelling: String,
        location: SourceLocation,
        diagnostics: Diagnostics,
    ): Expr {
        var body = lower
        var suffix = FloatSuffix.None
        when (body.lastOrNull()) {
            'f' -> if (!isHex || 'p' in body) {
                suffix = FloatSuffix.Float
                body = body.dropLast(1)
            }

            'h' -> {
                suffix = FloatSuffix.Half
                body = body.dropLast(1)
            }
        }
        val value = if (isHex) parseHexFloat(body) else body.toDoubleOrNull()
        if (value == null) {
            diagnostics.error(location, "invalid floating-point literal '$text'")
            return FloatLiteralExpr(0.0, suffix, spelling, location)
        }
        return FloatLiteralExpr(value, suffix, spelling, location)
    }

    private fun parseHexFloat(body: String): Double? {
        val exponentIndex = body.indexOf('p')
        if (exponentIndex < 0) return null
        val mantissa = body.substring(2, exponentIndex)
        val exponent = body.substring(exponentIndex + 1).toIntOrNull() ?: return null
        val dot = mantissa.indexOf('.')
        val digits = mantissa.replace(".", "")
        if (digits.isEmpty()) return null
        var value = 0.0
        for (c in digits) {
            val digit = c.digitToIntOrNull(16) ?: return null
            value = value * 16 + digit
        }
        val fractionDigits = if (dot < 0) 0 else mantissa.length - dot - 1
        return value * 2.0.pow(exponent - 4 * fractionDigits)
    }

    private fun parseInteger(lower: String, spelling: String, location: SourceLocation, diagnostics: Diagnostics): Expr {
        var body = lower
        var isUnsigned = false
        var isLong = false
        while (body.isNotEmpty()) {
            when (body.last()) {
                'u' -> isUnsigned = true
                'l' -> isLong = true
                else -> break
            }
            body = body.dropLast(1)
        }
        val (digits, radix) = when {
            body.startsWith("0x") -> body.substring(2) to 16
            body.startsWith("0b") -> body.substring(2) to 2
            body.length > 1 && body.startsWith("0") -> body.substring(1) to 8
            else -> body to 10
        }
        val value = digits.toULongOrNull(radix)
        if (value == null) {
            diagnostics.error(location, "invalid integer literal '$spelling'")
            return IntLiteralExpr(0, isUnsigned, isLong, spelling, location)
        }
        val signed = value.toLong()
        if (!isLong) {
            val fitsInt = if (isUnsigned || radix != 10) value <= UInt.MAX_VALUE.toULong() else signed in 0..Int.MAX_VALUE
            if (!fitsInt) isLong = true
            if (!isUnsigned && radix != 10 && value > Int.MAX_VALUE.toULong() && value <= UInt.MAX_VALUE.toULong()) {
                isUnsigned = true
                isLong = false
            }
        }
        return IntLiteralExpr(signed, isUnsigned, isLong, spelling, location)
    }
}
