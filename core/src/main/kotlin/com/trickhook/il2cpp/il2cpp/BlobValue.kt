package com.trickhook.il2cpp.il2cpp

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import kotlin.math.abs

data class BlobValue(
    val typeEnum: Il2CppTypeEnum,
    val value: Any?,
    val enumType: Il2CppType? = null
)

internal object ManagedNumber {

    private const val SINGLE_PRECISION = 9
    private const val DOUBLE_PRECISION = 17

    fun format(value: Float): String {
        val symbols = DecimalFormatSymbols.getInstance()
        if (value.isNaN()) return symbols.naN
        if (value == Float.POSITIVE_INFINITY) return symbols.infinity
        if (value == Float.NEGATIVE_INFINITY) return "-" + symbols.infinity
        val negative = value.toRawBits() < 0
        val magnitude = abs(value)
        if (magnitude == 0.0f) return if (negative) "-0" else "0"
        val exact = BigDecimal(magnitude.toDouble())
        var shortest = exact.round(MathContext(SINGLE_PRECISION, RoundingMode.HALF_EVEN))
        for (precision in 1 until SINGLE_PRECISION) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (candidate.toString().toFloat() == magnitude) {
                shortest = candidate
                break
            }
        }
        return render(negative, shortest.stripTrailingZeros(), SINGLE_PRECISION, symbols.decimalSeparator)
    }

    fun format(value: Double): String {
        val symbols = DecimalFormatSymbols.getInstance()
        if (value.isNaN()) return symbols.naN
        if (value == Double.POSITIVE_INFINITY) return symbols.infinity
        if (value == Double.NEGATIVE_INFINITY) return "-" + symbols.infinity
        val negative = value.toRawBits() < 0
        val magnitude = abs(value)
        if (magnitude == 0.0) return if (negative) "-0" else "0"
        val exact = BigDecimal(magnitude)
        var shortest = exact.round(MathContext(DOUBLE_PRECISION, RoundingMode.HALF_EVEN))
        for (precision in 1 until DOUBLE_PRECISION) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (candidate.toString().toDouble() == magnitude) {
                shortest = candidate
                break
            }
        }
        return render(negative, shortest.stripTrailingZeros(), DOUBLE_PRECISION, symbols.decimalSeparator)
    }

    private fun render(negative: Boolean, value: BigDecimal, precision: Int, separator: Char): String {
        val digits = value.unscaledValue().toString()
        val scale = digits.length - value.scale()
        val exponent = scale - 1
        val builder = StringBuilder(digits.length + 8)
        if (negative) builder.append('-')
        if (exponent > -5 && exponent < precision) {
            when {
                scale <= 0 -> {
                    builder.append('0').append(separator)
                    repeat(-scale) { builder.append('0') }
                    builder.append(digits)
                }
                scale >= digits.length -> {
                    builder.append(digits)
                    repeat(scale - digits.length) { builder.append('0') }
                }
                else -> builder.append(digits, 0, scale).append(separator).append(digits, scale, digits.length)
            }
        } else {
            builder.append(digits[0])
            if (digits.length > 1) builder.append(separator).append(digits, 1, digits.length)
            builder.append('E').append(if (exponent < 0) '-' else '+')
            val magnitude = abs(exponent)
            if (magnitude < 10) builder.append('0')
            builder.append(magnitude)
        }
        return builder.toString()
    }
}

internal fun escapeManagedString(source: String): String {
    val builder = StringBuilder(source.length)
    for (c in source) {
        when (c.code) {
            0x27 -> builder.append("\\'")
            0x22 -> builder.append("\\\"")
            0x5c -> builder.append("\\\\")
            0x00 -> builder.append("\\0")
            0x07 -> builder.append("\\a")
            0x08 -> builder.append("\\b")
            0x0c -> builder.append("\\f")
            0x0a -> builder.append("\\n")
            0x0d -> builder.append("\\r")
            0x09 -> builder.append("\\t")
            0x0b -> builder.append("\\v")
            0x85 -> builder.append("\\u0085")
            0x2028 -> builder.append("\\u2028")
            0x2029 -> builder.append("\\u2029")
            else -> builder.append(c)
        }
    }
    return builder.toString()
}

internal fun renderManagedLiteral(value: Any?, renderType: (Il2CppType) -> String): String = when (value) {
    null -> "null"
    is String -> "\"" + escapeManagedString(value) + "\""
    is Char -> "'\\x" + value.code.toString(16) + "'"
    is Boolean -> if (value) "True" else "False"
    is Float -> ManagedNumber.format(value)
    is Double -> ManagedNumber.format(value)
    is Il2CppType -> "typeof(" + renderType(value) + ")"
    is Array<*> -> value.joinToString(", ", "new[] { ", " }") { element ->
        if (element is BlobValue) renderBlobLiteral(element, renderType) else renderManagedLiteral(element, renderType)
    }
    else -> value.toString()
}

internal fun renderBlobLiteral(blob: BlobValue, renderType: (Il2CppType) -> String): String =
    renderManagedLiteral(blob.value, renderType)
