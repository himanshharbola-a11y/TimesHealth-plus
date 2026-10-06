package timeshealth.app.core.domain

import java.math.BigInteger

/*
 * The rules in this module were written in TypeScript and are still enforced
 * by a Node server. Where JavaScript and the JVM disagree on a primitive —
 * what "whitespace" is, how a string becomes a number — the port uses the
 * JavaScript meaning, so the phone and the server never disagree about the
 * same input. The iOS port needs the same care (see README "Dialect notes").
 */

/**
 * JavaScript's whitespace (ECMA-262 WhiteSpace + LineTerminator): exactly what
 * `String.prototype.trim()` strips and what `\s` matches in a JS regex.
 *
 * Why not Kotlin's own: `Char.isWhitespace()` also strips U+001C–U+001F and
 * keeps U+FEFF, and Java's regex `\s` is ASCII-only — so a pasted
 * "name @x.com" would pass a Java `[^\s@]` that JavaScript rejects.
 */
internal const val JS_WHITESPACE_CLASS =
    "\\t\\n\\u000B\\f\\r\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ',
    ' ', ' ', ' ', ' ', '　', '﻿',
    -> true
    in ' '..' ' -> true
    else -> false
}

/**
 * `String.prototype.trim()`. Use it before validating user input, as the RN
 * screens and the server (zod `.trim()`) do, so all three trim identically.
 */
fun String.trimJs(): String = trim(::isJsWhitespace)

private val JS_DECIMAL = Regex("[+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")
private val JS_RADIX = Regex("0([xXoObB])([0-9a-fA-F]+)")

/**
 * JavaScript's `Number(string)`: trimmed, "" is 0, hex/octal/binary literals
 * are allowed, anything else unparseable is NaN. Used where the TS split an
 * "HH:mm" batch time with `.map(Number)`, so " 5:15" still means 05:15.
 */
internal fun jsNumber(s: String): Double {
    val t = s.trimJs()
    if (t.isEmpty()) return 0.0
    if (JS_DECIMAL.matches(t)) {
        return when {
            t.endsWith("Infinity") -> if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            else -> t.toDouble()
        }
    }
    val radixLiteral = JS_RADIX.matchEntire(t) ?: return Double.NaN
    val radix = when (radixLiteral.groupValues[1].lowercase()) {
        "x" -> 16
        "o" -> 8
        else -> 2
    }
    val digits = radixLiteral.groupValues[2]
    if (digits.any { Character.digit(it, radix) < 0 }) return Double.NaN
    return BigInteger(digits, radix).toDouble()
}

/** A whole number from [jsNumber], or null for NaN, ±Infinity or a fraction. */
internal fun jsWholeNumber(s: String): Int? {
    val n = jsNumber(s)
    if (!n.isFinite() || n != Math.floor(n) || Math.abs(n) > Int.MAX_VALUE) return null
    return n.toInt()
}
