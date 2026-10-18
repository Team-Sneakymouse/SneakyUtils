package com.sneakyutils.util

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextUtilityTest {

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
