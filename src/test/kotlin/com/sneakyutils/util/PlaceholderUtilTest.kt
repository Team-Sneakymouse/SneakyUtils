package com.sneakyutils.util

import com.sneakyutils.fixture.server.PapiServer
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.nio.file.Path

class PlaceholderUtilTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: PapiServer
    private lateinit var singleton: MockedStatic<PlaceholderAPIPlugin>
    private lateinit var manager: LocalExpansionManager

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock(PapiServer())
        val plugin = mock(PlaceholderAPIPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(directory.toFile())
        `when`(plugin.logger).thenReturn(java.util.logging.Logger.getLogger("papi-test"))
        `when`(plugin.placeholderAPIConfig).thenReturn(mock(PlaceholderAPIConfig::class.java))
        manager = LocalExpansionManager(plugin)
        `when`(plugin.localExpansionManager).thenReturn(manager)
        singleton = mockStatic(PlaceholderAPIPlugin::class.java)
        singleton.`when`<PlaceholderAPIPlugin> { PlaceholderAPIPlugin.getInstance() }.thenReturn(plugin)
    }

    @AfterEach
    fun tearDown() {
        singleton.close()
        MockBukkit.unmock()
    }

    private fun expansion(identifier: String, parameter: String, result: () -> String?): PlaceholderExpansion {
        val expansion = object : PlaceholderExpansion() {
            override fun getIdentifier() = identifier
            override fun getAuthor() = "test"
            override fun getVersion() = "1"
            override fun onPlaceholderRequest(player: Player?, params: String): String? =
                if (params == parameter) result() else null
        }
        assertTrue(expansion.register())
        return expansion
    }

    @Test
    fun `returns text unchanged without PlaceholderAPI`() {
        val player = server.addPlayer()
        assertFalse(PlaceholderUtil.isAvailable())
        assertEquals("%test_value%", PlaceholderUtil.setPlaceholders(player, "%test_value%"))
        assertNull(PlaceholderUtil.setPlaceholders(player, null))
        assertEquals("plain", PlaceholderUtil.setPlaceholders(player, "plain"))
    }

    @Test
    fun `expands placeholders when PlaceholderAPI is enabled`() {
        MockBukkit.createMockPlugin("PlaceholderAPI")
        expansion("demo", "value") { "ok" }
        val player = server.addPlayer()
        assertTrue(PlaceholderUtil.isAvailable())
        assertEquals("ok", PlaceholderUtil.setPlaceholders(player, "%demo_value%"))
        assertEquals("prefix ok", PlaceholderUtil.setPlaceholders(player, "prefix %demo_value%"))
    }
}
