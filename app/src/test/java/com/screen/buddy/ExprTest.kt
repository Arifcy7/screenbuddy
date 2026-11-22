package com.screen.buddy

import com.screen.buddy.overlay.math.Expr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class ExprTest {
    private fun eval(source: String, x: Double = 0.0, y: Double = 0.0) = Expr.parse(source).eval(x, y)

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(14.0, eval("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, eval("(2 + 3) * 4"), 1e-9)
        assertEquals(-9.0, eval("-3^2"), 1e-9)
        assertEquals(512.0, eval("2^3^2"), 1e-9)
        assertEquals(0.25, eval("2^-2"), 1e-9)
    }

    @Test
    fun variablesAndImplicitMultiplication() {
        assertEquals(6.0, eval("x^2 - 3x + 2", x = 4.0), 1e-9)
        assertEquals(15.0, eval("(x+1)(x-1)", x = 4.0), 1e-9)
        assertEquals(2 * sin(3.0), eval("2sin(x)", x = 3.0), 1e-9)
        assertEquals(12.0, eval("x y", x = 3.0, y = 4.0), 1e-9)
    }

    @Test
    fun functionsAndConstants() {
        assertEquals(0.0, eval("sin(pi)"), 1e-9)
        assertEquals(1.0 / (1.0 + exp(-2.0)), eval("sigmoid(x)", x = 2.0), 1e-9)
        assertEquals(1.0 / (1.0 + exp(-2.0)), eval("1/(1+exp(-x))", x = 2.0), 1e-9)
        assertEquals(0.0, eval("relu(x)", x = -5.0), 1e-9)
        assertEquals(5.0, eval("sqrt(x^2 + y^2)", x = 3.0, y = 4.0), 1e-9)
        assertEquals(3.0, eval("|x|", x = -3.0), 1e-9)
        assertEquals(7.0, eval("max(x, 7)", x = 2.0), 1e-9)
        assertEquals(sqrt(2.0), eval("2 ** 0.5"), 1e-9)
    }

    @Test
    fun acceptsEquationForms() {
        assertEquals(9.0, eval("y = x^2", x = 3.0), 1e-9)
        assertEquals(9.0, eval("f(x) = x²", x = 3.0), 1e-9)
        assertEquals(0.001, eval("1e-3"), 1e-12)
        assertEquals(2 * Math.E, eval("2e"), 1e-9)
    }

    @Test
    fun rejectsNonsense() {
        assertThrows(IllegalArgumentException::class.java) { Expr.parse("x +") }
        assertThrows(IllegalArgumentException::class.java) { Expr.parse("foo(x)") }
        assertThrows(IllegalArgumentException::class.java) { Expr.parse("sin(x") }
        assertThrows(IllegalArgumentException::class.java) { Expr.parse("") }
    }
}
