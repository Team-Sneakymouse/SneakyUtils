package com.sneakyutils.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Locale;
import org.junit.jupiter.api.Test;

final class ServerUtilTest {

    @Test
    void serverName_preservesSuppliedValueExactly() {
        assertEquals("Dev Main", ServerUtil.serverNameOf("Dev Main"));
        assertEquals(" Dev  Main! ", ServerUtil.serverNameOf(" Dev  Main! "));
    }

    @Test
    void serverName_isNullWhenMissingOrBlank() {
        assertNull(ServerUtil.serverNameOf(null));
        assertNull(ServerUtil.serverNameOf(""));
        assertNull(ServerUtil.serverNameOf(" \t\n "));
        assertNull(ServerUtil.serverNameOf("  "));
    }

    @Test
    void serverId_isKebabCaseOfName() {
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev Main"));
    }

    @Test
    void serverId_collapsesWhitespaceAndPunctuationRunsToOneHyphen() {
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev  \t Main"));
        assertEquals("dev-main", ServerUtil.serverIdOf("Dev_Main"));
        assertEquals("dev-main-2", ServerUtil.serverIdOf("Dev -- Main: #2"));
        assertEquals("lom-eu-1", ServerUtil.serverIdOf("LoM (EU) 1"));
    }

    @Test
    void serverId_keepsNonAsciiLettersAndDigits() {
        assertEquals("café-main-٣", ServerUtil.serverIdOf("Café Main ٣"));
    }

    @Test
    void serverId_stripsBoundaryHyphens() {
        assertEquals("dev-main", ServerUtil.serverIdOf("  [Dev Main]! "));
        assertEquals("dev-main", ServerUtil.serverIdOf("--dev-main--"));
    }

    @Test
    void serverId_isNullWhenNothingIdentifyingRemains() {
        assertNull(ServerUtil.serverIdOf(null));
        assertNull(ServerUtil.serverIdOf(""));
        assertNull(ServerUtil.serverIdOf(" \t "));
        assertNull(ServerUtil.serverIdOf("--- !!! ---"));
    }

    @Test
    void serverId_lowercasesIndependentlyOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("insider-info", ServerUtil.serverIdOf("INSIDER INFO"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
