package ai.hybrid.commandcenter.nexus

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact arithmetic for questions like «сколько будет 0,1 + 0,2» or «15% от 2400».
 * Language models guess digits; this computes them with BigDecimal, so
 * 0,1 + 0,2 is exactly 0,3 and big numbers keep every digit.
 */
object ExactCalculator {
    private val CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
    private const val DIVISION_SCALE = 20

    class CalcException(message: String) : IllegalArgumentException(message)

    /** Turns Russian words into operators: «два плюс два умножить на три» stays numeric-only, digits required. */
    fun normalize(input: String): String {
        var s = input.lowercase()
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace(':', '/')
            .replace('−', '-').replace('–', '-')
        val words = listOf(
            "умножить на" to "*", "умножь на" to "*", "помножить на" to "*",
            "разделить на" to "/", "раздели на" to "/", "делить на" to "/", "поделить на" to "/",
            "в степени" to "^", "плюс" to "+", "минус" to "-"
        )
        for ((word, op) in words) s = s.replace(word, " $op ")
        // «15% от 2400» / «15 процентов от 2400» → (15/100*2400)
        s = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:%|процент(?:а|ов)?)\\s+от\\s+(\\d+(?:[.,]\\d+)?)")
            .replace(s) { m -> "(" + m.groupValues[1] + "/100*" + m.groupValues[2] + ")" }
        s = Regex("корень\\s+(?:квадратный\\s+)?из\\s+").replace(s, "√")
        s = s.replace(Regex("процент(?:а|ов)?"), "%")
        // decimal comma between digits; spaces inside numbers («1 000 000»)
        s = Regex("(\\d),(\\d)").replace(s, "$1.$2")
        s = Regex("(\\d)\\s+(?=\\d{3}(?!\\d))").replace(s, "$1")
        return s.trim()
    }

    fun evaluate(expression: String): BigDecimal {
        val parser = Parser(normalize(expression))
        val value = parser.expression()
        parser.skipSpaces()
        if (!parser.done) throw CalcException("Не понял выражение после «" + parser.rest() + "».")
        return value
    }

    /** «3,5», «1 234 567», «0,333333…» — Russian formatting with every significant digit. */
    fun format(value: BigDecimal): String {
        val plain = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        val negative = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val intPart = body.substringBefore('.')
        val fraction = body.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
        return (if (negative) "-" else "") + grouped + if (fraction.isNotEmpty()) ",$fraction" else ""
    }

    /** True when the text is mostly an arithmetic expression worth computing exactly. */
    fun looksLikeMath(text: String): Boolean {
        val s = normalize(text)
        val digits = s.count { it.isDigit() }
        val ops = s.count { it in "+-*/^%√" }
        return digits >= 1 && ops >= 1 && s.count { it.isLetter() } <= 2
    }

    private class Parser(private val s: String) {
        private var i = 0
        val done: Boolean get() = i >= s.length
        fun rest(): String = s.substring(i).take(12)

        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun peek(): Char? { skipSpaces(); return s.getOrNull(i) }

        fun expression(): BigDecimal {
            var value = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; value = value.add(term(), CONTEXT) }
                    '-' -> { i++; value = value.subtract(term(), CONTEXT) }
                    else -> return value
                }
            }
        }

        private fun term(): BigDecimal {
            var value = power()
            while (true) {
                when (peek()) {
                    '*' -> { i++; value = value.multiply(power(), CONTEXT) }
                    '/' -> {
                        i++
                        val divisor = power()
                        if (divisor.signum() == 0) throw CalcException("На ноль делить нельзя.")
                        value = value.divide(divisor, DIVISION_SCALE, RoundingMode.HALF_EVEN)
                    }
                    else -> return value
                }
            }
        }

        private fun power(): BigDecimal {
            val base = unary()
            if (peek() == '^') {
                i++
                val exponent = power()
                val n = exponent.stripTrailingZeros()
                if (n.scale() > 0 || n.abs() > BigDecimal(1000)) throw CalcException("Степень должна быть целой и не больше 1000.")
                val k = n.toInt()
                return if (k >= 0) base.pow(k, CONTEXT)
                else BigDecimal.ONE.divide(base.pow(-k, CONTEXT), DIVISION_SCALE, RoundingMode.HALF_EVEN)
            }
            return base
        }

        private fun unary(): BigDecimal = when (peek()) {
            '-' -> { i++; unary().negate() }
            '+' -> { i++; unary() }
            '√' -> { i++; sqrt(unary()) }
            else -> postfix(primary())
        }

        private fun postfix(value: BigDecimal): BigDecimal =
            if (peek() == '%') { i++; value.divide(BigDecimal(100)) } else value

        private fun primary(): BigDecimal {
            val c = peek() ?: throw CalcException("Выражение оборвалось.")
            if (c == '(') {
                i++
                val inner = expression()
                if (peek() != ')') throw CalcException("Не хватает закрывающей скобки.")
                i++
                return inner
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw CalcException("Ожидалось число, а встретилось «" + c + "».")
            return s.substring(start, i).toBigDecimalOrNull() ?: throw CalcException("Странное число: " + s.substring(start, i))
        }

        private fun sqrt(value: BigDecimal): BigDecimal {
            if (value.signum() < 0) throw CalcException("Корень из отрицательного числа не вещественный.")
            if (value.signum() == 0) return BigDecimal.ZERO
            var x = BigDecimal(Math.sqrt(value.toDouble()), CONTEXT)
            repeat(8) { x = x.add(value.divide(x, CONTEXT)).divide(BigDecimal(2), CONTEXT) }
            return x.setScale(DIVISION_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
        }
    }
}
