package com.sneakyutils.util

import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextUtilityTest {

    @Test
    fun convertToComponent_decodesBadgeEscapesWhilePreservingFontAndFollowingName() {
        for (badge in listOf("005", "055", "555")) {
            val glyph = ("E$badge".toInt(16)).toChar()
            val expected = MiniMessage.miniMessage()
                .deserialize("<font:lom:badges>$glyph</font> <gold>Name")
                .decoration(TextDecoration.ITALIC, false)
            val actual = TextUtility.convertToComponent("<font:lom:badges>\\uE$badge</font> &6Name")

            assertEquals(expected, actual, "badge $badge must decode without changing font scope")
            assertEquals("$glyph Name", PlainTextComponentSerializer.plainText().serialize(actual))
        }
    }

    @Test
    fun convertToComponent_decodesLongEscapesAndSurrogatePairs() {
        val actual = TextUtility.convertToComponent("\\U0001F50A \\uD83D\\uDD0A \\u00e9 \\u0041")
        assertEquals("🔊 🔊 é A", PlainTextComponentSerializer.plainText().serialize(actual))
    }

    @Test
    fun convertToComponent_preservesMalformedAndOutOfRangeEscapes() {
        for (input in listOf("", "plain", "é 🔊", "\\u12", "\\uZZZZ", "\\U0000ZZZZ", "\\U00110000", "\\UFFFFFFFF")) {
            assertEquals(input, PlainTextComponentSerializer.plainText().serialize(TextUtility.convertToComponent(input)))
        }
    }

    @Test
    fun convertToComponent_decodesBeforeLegacyFormatting() {
        assertEquals(
            TextUtility.convertToComponent("&aGreen &6Gold"),
            TextUtility.convertToComponent("\\u00A7aGreen \\u00266Gold")
        )
    }

    @Test
    fun replaceFormatCodes_convertsLegacyAmpersandColors() {
        val converted = TextUtility.replaceFormatCodes("&cHello")
        assertEquals("<red>Hello", converted)
    }

    @Test
    fun replaceFormatCodes_convertsSectionSignToAmpersand() {
        val converted = TextUtility.replaceFormatCodes("\u00A7aOK")
        assertEquals("<green>OK", converted)
    }

    @Test
    fun convertToComponent_stripsItalicByDefault() {
        val plain =
            PlainTextComponentSerializer.plainText()
                .serialize(TextUtility.convertToComponent("&7plain"))
        assertEquals("plain", plain)
    }

    @Test
    fun splitIntoLines_preservesWordsWhenWrapping() {
        val input = "one two three four five six"
        val lines = TextUtility.splitIntoLines(input, maxLineLength = 10)
        assertEquals(input, lines.joinToString(" "))
    }

    @Test
    fun splitIntoLines_keepsShortTextOnOneLine() {
        assertEquals(listOf("hello"), TextUtility.splitIntoLines("hello", maxLineLength = 80))
    }

    @Test
    fun splitIntoLines_neverExceedsMaxExceptOversizedWord() {
        val lines = TextUtility.splitIntoLines("alpha bravo charlie delta echo", maxLineLength = 12)
        assertTrue(lines.all { it.length <= 12 || !it.contains(' ') })
    }

    @Test
    fun splitIntoLines_doesNotDumpRemainderOntoLongFinalLine() {
        // Old average + remainingLines==1 algorithm packed early lines near the mean, then dumped
        // leftovers on the last line (here: "dddd eeee f" at length 11 with max 10).
        val input = "aaaa bbbb cccc dddd eeee f"
        val lines = TextUtility.splitIntoLines(input, maxLineLength = 10)
        assertTrue(lines.all { it.length <= 10 }, "oversized line in $lines")
        assertEquals(input, lines.joinToString(" "))
        assertTrue(lines.size >= 3)
    }

    @Test
    fun splitIntoLines_keepsOversizedWordIntact() {
        assertEquals(listOf("supercalifragilistic"), TextUtility.splitIntoLines("supercalifragilistic", maxLineLength = 5))
    }
}
