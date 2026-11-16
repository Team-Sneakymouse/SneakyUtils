package com.sneakyutils.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.plugin.Plugin

class PermissionUtilityTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun hasNodeNeedsTheExactNodeGranted() {
        val p = server.addPlayer()
        p.addAttachment(plugin, "feature.use", false)
        p.addAttachment(plugin, "feature.use.extra", true)
        assertFalse(PermissionUtility.hasNode(p, "feature.use"))
        assertFalse(PermissionUtility.playerHasNode(p.uniqueId.toString(), "feature.use"))
        p.addAttachment(plugin, "feature.other", true)
        assertTrue(PermissionUtility.hasNode(p, "feature.other"))
        assertTrue(PermissionUtility.playerHasNode(p.uniqueId.toString(), "feature.other"))
    }

    @Test
    fun getMaxNumberCountsOnlyGrantedNumericNodes() {
        val p = server.addPlayer()
        p.addAttachment(plugin, "voice.range.8", true)
        p.addAttachment(plugin, "voice.range.64", false)
        p.addAttachment(plugin, "voice.range.big", true)
        p.addAttachment(plugin, "voice.rangeextra.99", true)
        assertEquals(8, PermissionUtility.getMaxNumber(p, "voice.range"))
        assertEquals(8, PermissionUtility.getMaxNumber(p.uniqueId.toString(), "voice.range"))
        assertEquals(0, PermissionUtility.getMaxNumber(p, "voice.none"))
    }
}
