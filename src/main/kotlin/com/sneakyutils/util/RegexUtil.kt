package com.sneakyutils.util

import java.util.regex.Pattern

object RegexUtil {

    @JvmField
    val DOUBLE_PATTERN: Pattern = Pattern.compile(
        "[\\x00-\\x20]*[+-]?(NaN|Infinity|((((\\p{Digit}+)(\\.)?((\\p{Digit}+)?)([eE][+-]?(\\p{Digit}+))?)|" +
            "(\\.((\\p{Digit}+))([eE][+-]?(\\p{Digit}+))?)|" +
            "(((0[xX](\\p{XDigit}+)(\\.)?)|(0[xX](\\p{XDigit}+)?(\\.)(\\p{XDigit}+)))[pP][+-]?(\\p{Digit}+)))[fFdD]?))" +
            "[\\x00-\\x20]*"
    )

    @JvmField
    val SIMPLE_INT_PATTERN: Pattern = Pattern.compile("^[0-9]+$")

    const val USERNAME_REGEXP = "[a-zA-Z0-9_]{3,16}"

    @JvmField
    val USERNAME_PATTERN: Pattern = Pattern.compile(USERNAME_REGEXP)

    @JvmField
    val BASIC_HEX_PATTERN: Pattern = Pattern.compile("[0-9A-Fa-f]+")

    @JvmField
    val BASIC_DECIMAL_INT_PATTERN: Pattern = Pattern.compile("[0-9]+")

    @JvmStatic
    fun matches(pattern: Pattern, string: String): Boolean =
        pattern.matcher(string).matches()

    @JvmStatic
    fun split(pattern: Pattern, string: String, max: Int): Array<String> =
        pattern.split(string, max)

    @JvmStatic
    fun replaceAll(pattern: Pattern, string: String, replacement: String): String =
        pattern.matcher(string).replaceAll(replacement)

    @JvmStatic
    fun replaceFirst(pattern: Pattern, string: String, replacement: String): String =
        pattern.matcher(string).replaceFirst(replacement)
}
