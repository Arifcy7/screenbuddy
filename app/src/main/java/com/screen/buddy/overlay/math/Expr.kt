package com.screen.buddy.overlay.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A small maths expression in x (and optionally y), e.g. `sin(x)`, `x^2 - 3x + 2`,
 * `1/(1+exp(-x))`, `sin(sqrt(x^2+y^2))`. Parsed once, then evaluated many times, so curves and
 * surfaces are computed exactly on the device instead of being sampled by the language model.
 */
class Expr private constructor(private val root: Node) {

    fun eval(x: Double, y: Double = 0.0): Double = root.eval(x, y)

    private fun interface Node {
        fun eval(x: Double, y: Double): Double
    }

    companion object {
        /** @throws IllegalArgumentException with a message the model can act on. */
        fun parse(source: String): Expr {
            var text = source.trim()
            // Accept "y = ...", "f(x) = ...", "z = ..." by keeping the right-hand side.
            val eq = text.lastIndexOf('=')
            if (eq >= 0) text = text.substring(eq + 1)
            require(text.isNotBlank()) { "Empty expression" }
            val parser = Parser(tokenize(text))
            val node = parser.expression()
            require(parser.atEnd()) { "Unexpected '${parser.peekText()}' in expression" }
            return Expr(node)
        }

        private val FUNCTIONS_1: Map<String, (Double) -> Double> = mapOf(
            "sin" to ::sin, "cos" to ::cos, "tan" to ::tan,
            "asin" to ::asin, "acos" to ::acos, "atan" to ::atan,
            "arcsin" to ::asin, "arccos" to ::acos, "arctan" to ::atan,
            "sinh" to ::sinh, "cosh" to ::cosh, "tanh" to ::tanh,
            "exp" to ::exp, "ln" to ::ln, "log" to ::log10, "log10" to ::log10, "log2" to ::log2,
            "sqrt" to ::sqrt, "abs" to { v -> abs(v) }, "floor" to ::floor, "ceil" to ::ceil,
            "sign" to { v -> sign(v) },
            "sigmoid" to { v -> 1.0 / (1.0 + exp(-v)) },
            "relu" to { v -> max(0.0, v) },
            "step" to { v -> if (v >= 0) 1.0 else 0.0 },
        )

        private val FUNCTIONS_2: Map<String, (Double, Double) -> Double> = mapOf(
            "max" to { a, b -> max(a, b) },
            "min" to { a, b -> min(a, b) },
            "pow" to { a, b -> a.pow(b) },
        )

        private val CONSTANTS = mapOf("pi" to Math.PI, "π" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI)

        private sealed class Token {
            data class Num(val value: Double) : Token()
            data class Name(val name: String) : Token()
            data class Sym(val c: Char) : Token()
        }

        private fun tokenize(s: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || c == '.' -> {
                        var j = i
                        while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                        // Scientific notation such as 1e-3 (but not "2e" meaning 2*e).
                        if (j < s.length && (s[j] == 'e' || s[j] == 'E')) {
                            var k = j + 1
                            if (k < s.length && (s[k] == '+' || s[k] == '-')) k++
                            if (k < s.length && s[k].isDigit()) {
                                while (k < s.length && s[k].isDigit()) k++
                                j = k
                            }
                        }
                        val value = s.substring(i, j).toDoubleOrNull()
                            ?: throw IllegalArgumentException("Bad number '${s.substring(i, j)}'")
                        out.add(Token.Num(value))
                        i = j
                    }
                    c.isLetter() -> {
                        var j = i
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                        out.add(Token.Name(s.substring(i, j).lowercase()))
                        i = j
                    }
                    c == '*' && i + 1 < s.length && s[i + 1] == '*' -> {
                        out.add(Token.Sym('^')) // Python-style power
                        i += 2
                    }
                    c == '×' || c == '·' -> { out.add(Token.Sym('*')); i++ }
                    c == '÷' -> { out.add(Token.Sym('/')); i++ }
                    c == '−' -> { out.add(Token.Sym('-')); i++ }
                    c == '²' -> { out.add(Token.Sym('^')); out.add(Token.Num(2.0)); i++ }
                    c == '³' -> { out.add(Token.Sym('^')); out.add(Token.Num(3.0)); i++ }
                    c in "+-*/^(),|" -> { out.add(Token.Sym(c)); i++ }
                    else -> throw IllegalArgumentException("Unexpected character '$c' in expression")
                }
            }
            return out
        }

        private class Parser(private val tokens: List<Token>) {
            private var pos = 0

            fun atEnd() = pos >= tokens.size

            fun peekText(): String = when (val t = tokens.getOrNull(pos)) {
                is Token.Num -> t.value.toString()
                is Token.Name -> t.name
                is Token.Sym -> t.c.toString()
                null -> "end"
            }

            private fun peekSym(c: Char) = (tokens.getOrNull(pos) as? Token.Sym)?.c == c

            private fun eat(c: Char): Boolean {
                if (peekSym(c)) {
                    pos++
                    return true
                }
                return false
            }

            private fun expect(c: Char) {
                require(eat(c)) { "Expected '$c' but found '${peekText()}'" }
            }

            fun expression(): Node {
                var left = term()
                while (true) {
                    left = when {
                        eat('+') -> { val a = left; val b = term(); Node { x, y -> a.eval(x, y) + b.eval(x, y) } }
                        eat('-') -> { val a = left; val b = term(); Node { x, y -> a.eval(x, y) - b.eval(x, y) } }
                        else -> return left
                    }
                }
            }

            private fun term(): Node {
                var left = unary()
                while (true) {
                    left = when {
                        eat('*') -> { val a = left; val b = unary(); Node { x, y -> a.eval(x, y) * b.eval(x, y) } }
                        eat('/') -> { val a = left; val b = unary(); Node { x, y -> a.eval(x, y) / b.eval(x, y) } }
                        // Implicit multiplication: 2x, 3sin(x), (x+1)(x-1), x y
                        startsPrimary() -> { val a = left; val b = power(); Node { x, y -> a.eval(x, y) * b.eval(x, y) } }
                        else -> return left
                    }
                }
            }

            private fun startsPrimary(): Boolean = when (val t = tokens.getOrNull(pos)) {
                is Token.Num, is Token.Name -> true
                is Token.Sym -> t.c == '('
                null -> false
            }

            private fun unary(): Node {
                if (eat('-')) {
                    val inner = unary()
                    return Node { x, y -> -inner.eval(x, y) }
                }
                if (eat('+')) return unary()
                return power()
            }

            private fun power(): Node {
                val base = primary()
                if (eat('^')) {
                    val exponent = unary() // right associative, allows 2^-x
                    return Node { x, y -> base.eval(x, y).pow(exponent.eval(x, y)) }
                }
                return base
            }

            private fun primary(): Node {
                val token = tokens.getOrNull(pos)
                    ?: throw IllegalArgumentException("Expression ended unexpectedly")
                pos++
                return when (token) {
                    is Token.Num -> Node { _, _ -> token.value }
                    is Token.Sym -> when (token.c) {
                        '(' -> expression().also { expect(')') }
                        '|' -> {
                            val inner = expression()
                            expect('|')
                            Node { x, y -> abs(inner.eval(x, y)) }
                        }
                        else -> throw IllegalArgumentException("Unexpected '${token.c}' in expression")
                    }
                    is Token.Name -> name(token.name)
                }
            }

            private fun name(name: String): Node {
                if (name == "x") return Node { x, _ -> x }
                if (name == "y") return Node { _, y -> y }
                CONSTANTS[name]?.let { c -> return Node { _, _ -> c } }
                FUNCTIONS_1[name]?.let { f ->
                    expect('(')
                    val arg = expression()
                    expect(')')
                    return Node { x, y -> f(arg.eval(x, y)) }
                }
                FUNCTIONS_2[name]?.let { f ->
                    expect('(')
                    val a = expression()
                    expect(',')
                    val b = expression()
                    expect(')')
                    return Node { x, y -> f(a.eval(x, y), b.eval(x, y)) }
                }
                throw IllegalArgumentException(
                    "Unknown name '$name'. Use x (and y), numbers, + - * / ^, pi, e and functions " +
                        "like sin, cos, tan, exp, ln, log, sqrt, abs, tanh, sigmoid, relu.",
                )
            }
        }
    }
}
