package com.sneakyutils.util

import org.bukkit.Color
import java.math.BigInteger
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Color helpers: Bukkit [Color] parsing plus legacy section-sign formatting
 * without `org.bukkit.ChatColor`.
 */
object ColorUtil {

    /** Legacy formatting character (`§`). */
    const val COLOR_CHAR = '\u00A7'

    @JvmField
    val HEX_PATTERN: Pattern = Pattern.compile("&(#\\w{6})")

    private const val ALL_CODES = "0123456789abcdefklmnorx"
    private val STRIP_COLOR_PATTERN: Pattern = Pattern.compile("(?i)$COLOR_CHAR[0-9A-FK-ORX]")

    @JvmField
    val BLACK: String = sectionCode('0')

    @JvmField
    val DARK_BLUE: String = sectionCode('1')

    @JvmField
    val DARK_GREEN: String = sectionCode('2')

    @JvmField
    val DARK_AQUA: String = sectionCode('3')

    @JvmField
    val DARK_RED: String = sectionCode('4')

    @JvmField
    val DARK_PURPLE: String = sectionCode('5')

    @JvmField
    val GOLD: String = sectionCode('6')

    @JvmField
    val GRAY: String = sectionCode('7')

    @JvmField
    val DARK_GRAY: String = sectionCode('8')

    @JvmField
    val BLUE: String = sectionCode('9')

    @JvmField
    val GREEN: String = sectionCode('a')

    @JvmField
    val AQUA: String = sectionCode('b')

    @JvmField
    val RED: String = sectionCode('c')

    @JvmField
    val LIGHT_PURPLE: String = sectionCode('d')

    @JvmField
    val YELLOW: String = sectionCode('e')

    @JvmField
    val WHITE: String = sectionCode('f')

    @JvmField
    val RESET: String = sectionCode('r')

    @JvmStatic
    fun sectionCode(code: Char): String =
        COLOR_CHAR.toString() + code.lowercaseChar()

    /**
     * Parse a single-character config color (`"3"`, `"a"`) into a section code
     * (`§3`). Falls back to [defaultCode] when missing or invalid.
     */
    @JvmStatic
    fun sectionCodeFromConfig(value: String?, defaultCode: Char): String {
        val parsed = parseColorChar(value)
        return sectionCode(parsed ?: defaultCode)
    }

    @JvmStatic
    fun parseColorChar(value: String?): Char? {
        if (value.isNullOrEmpty()) return null
        val c = value[0].lowercaseChar()
        if (ALL_CODES.indexOf(c) < 0) return null
        return c
    }

    /** MiniMessage tag for a legacy color/format code char, or `null` if unknown. */
    @JvmStatic
    fun miniMessageTagFor(code: Char): String? =
        when (code.lowercaseChar()) {
            '0' -> "black"
            '1' -> "dark_blue"
            '2' -> "dark_green"
            '3' -> "dark_aqua"
            '4' -> "dark_red"
            '5' -> "dark_purple"
            '6' -> "gold"
            '7' -> "gray"
            '8' -> "dark_gray"
            '9' -> "blue"
            'a' -> "green"
            'b' -> "aqua"
            'c' -> "red"
            'd' -> "light_purple"
            'e' -> "yellow"
            'f' -> "white"
            'k' -> "obfuscated"
            'l' -> "bold"
            'm' -> "strikethrough"
            'n' -> "underlined"
            'o' -> "italic"
            'r' -> "reset"
            else -> null
        }

    /**
     * Same rules as Bukkit `ChatColor.translateAlternateColorCodes`: replace
     * [altColorChar] + valid code with section-sign form. Does not touch `&#rrggbb`.
     */
    @JvmStatic
    fun translateAlternateColorCodes(altColorChar: Char, textToTranslate: String?): String? {
        if (textToTranslate.isNullOrEmpty()) return textToTranslate
        val b = textToTranslate.toCharArray()
        for (i in 0 until b.size - 1) {
            if (b[i] == altColorChar && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = COLOR_CHAR
                b[i + 1] = b[i + 1].lowercaseChar()
            }
        }
        return String(b)
    }

    @JvmStatic
    fun stripColor(input: String?): String? {
        if (input == null) return null
        return STRIP_COLOR_PATTERN.matcher(input).replaceAll("")
    }

    /** Translate `&` codes and expand `&#rrggbb` into weird-hex section form. */
    @JvmStatic
    fun colorize(string: String?): String? {
        if (string == null || string.isEmpty()) return if (string == null) null else ""

        val matcher = HEX_PATTERN.matcher(translateAlternateColorCodes('&', string)!!)
        val builder = StringBuilder()
        while (matcher.find()) {
            val code = matcher.group(1)

            val magic = StringBuilder()
            magic.append(COLOR_CHAR).append('x')
            for (i in 1 until code.length) magic.append(COLOR_CHAR).append(code[i])

            matcher.appendReplacement(builder, Matcher.quoteReplacement(magic.toString()))
        }

        return matcher.appendTail(builder).toString()
    }

    @JvmStatic
    fun decolorize(string: String?): String? {
        if (string == null || string.isEmpty()) return if (string == null) null else ""
        return stripColor(colorize(string))
    }

    @JvmStatic
    @JvmOverloads
    fun getColorFromHexString(hex: String?, debug: Boolean = true): Color? {
        if (hex == null) return null

        val working = hex.replace("#", "")
        return try {
            val value = working.toInt(16)
            Color.fromRGB(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @JvmStatic
    fun getColorFromARGHexString(hex: String?, debug: Boolean): Color? {
        if (hex == null) return null

        var working = hex
        if (working.startsWith("#")) working = working.substring(1)

        return try {
            val value = BigInteger(working, 16).toInt()
            Color.fromARGB(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @JvmStatic
    fun getColorFromRGBString(value: String?): Color? {
        if (value == null) return null
        val splits = value.split(",")
        if (splits.size < 3) return null

        return try {
            val red = splits[0].toInt()
            val green = splits[1].toInt()
            val blue = splits[2].toInt()
            Color.fromRGB(red, green, blue)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Resolve a color enum name (`RED`, `dark_aqua`) to a section code. */
    @JvmStatic
    fun sectionCodeFromName(name: String?, fallback: String): String {
        if (name.isNullOrEmpty()) return fallback
        val key = name.trim().lowercase(Locale.ROOT).replace('-', '_')
        return when (key) {
            "black" -> BLACK
            "dark_blue" -> DARK_BLUE
            "dark_green" -> DARK_GREEN
            "dark_aqua" -> DARK_AQUA
            "dark_red" -> DARK_RED
            "dark_purple" -> DARK_PURPLE
            "gold" -> GOLD
            "gray", "grey" -> GRAY
            "dark_gray", "dark_grey" -> DARK_GRAY
            "blue" -> BLUE
            "green" -> GREEN
            "aqua" -> AQUA
            "red" -> RED
            "light_purple" -> LIGHT_PURPLE
            "yellow" -> YELLOW
            "white" -> WHITE
            else -> fallback
        }
    }
}
