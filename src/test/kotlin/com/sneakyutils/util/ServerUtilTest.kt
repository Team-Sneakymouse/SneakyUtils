package com.sneakyutils.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class ServerUtilTest {

    @Test
    fun serverName_preservesSuppliedValueExactly() {
        assertEquals("Dev Main", ServerUtil.serverNameOf("Dev Main"))
        assertEquals(" Dev  Main! ", ServerUtil.serverNameOf(" Dev  Main! "))
    }

    @Test
    fun serverName_isNullWhenMissingOrBlank() {
        assertNull(ServerUtil.serverNameOf(null))
        assertNull(ServerUtil.serverNameOf(""))
        assertNull(ServerUtil.serverNameOf(" \t\n "))
    }

    @Test
    fun serverId_isKebabCaseOfName() {
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev Main"))
    }

    @Test
    fun serverId_collapsesWhitespaceAndPunctuationRunsToOneHyphen() {
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev  \t Main"))
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev_Main"))
        assertEquals("dev-main-2", ServerUtil.serverIdOf("Dev -- Main: #2"))
        assertEquals("lom-eu-1", ServerUtil.serverIdOf("LoM (EU) 1"))
    }

    @Test
    fun serverId_keepsNonAsciiLettersAndDigits() {
        assertEquals("café-main-٣", ServerUtil.serverIdOf("Café Main ٣"))
    }

    @Test
    fun serverId_stripsBoundaryHyphens() {
        assertEquals("dev-main", ServerUtil.serverIdOf("  [Dev Main]! "))
        assertEquals("dev-main", ServerUtil.serverIdOf("--dev-main--"))
    }

    @Test
    fun serverId_isNullWhenNothingIdentifyingRemains() {
        assertNull(ServerUtil.serverIdOf(null))
        assertNull(ServerUtil.serverIdOf(""))
        assertNull(ServerUtil.serverIdOf(" \t "))
        assertNull(ServerUtil.serverIdOf("--- !!! ---"))
    }

    @Test
    fun serverId_lowercasesIndependentlyOfDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("insider-info", ServerUtil.serverIdOf("INSIDER INFO"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
