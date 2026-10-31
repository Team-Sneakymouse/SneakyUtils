package com.sneakyutils.util

import com.sneakyutils.fixture.server.PapiServer
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.nio.file.Path

class PlayerUtilTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: PapiServer
    private lateinit var singleton: MockedStatic<PlaceholderAPIPlugin>
    private lateinit var manager: LocalExpansionManager

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock(PapiServer())
        // Only the plugin lifecycle singleton is mocked: the PAPI registry,
        // expansion registration, parsing and dispatch below are the real API.
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

    private fun enableNames() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        MockBukkit.createMockPlugin("PlaceholderAPI")
    }

    @Test
    fun `registered noformat expansion output is preserved and current`() {
        enableNames()
        var name = " <literal> &xMomo §a %other_placeholder% "
        expansion("sneakycharacters", "character_name_noformat") { name }
        val player = server.addPlayer("MinecraftName")
        assertEquals(name, PlayerUtil.getCharacterName(player))
        name = " Renamed "
        assertEquals(name, PlayerUtil.getCharacterName(player))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "\t\n", "%sneakycharacters_character_name_noformat%"])
    fun `unavailable name falls back`(name: String?) {
        enableNames()
        expansion("sneakycharacters", "character_name_noformat") { name }
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(server.addPlayer("MinecraftName")))
    }

    @Test
    fun `missing expansion or absent or disabled plugins falls back and recovers`() {
        val player = server.addPlayer("MinecraftName")
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(player))
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(player))
        val papi = MockBukkit.createMockPlugin("PlaceholderAPI")
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(player))
        expansion("sneakycharacters", "character_name_noformat") { "Momo" }
        assertEquals("Momo", PlayerUtil.getCharacterName(player))
        server.pluginManager.disablePlugin(scm)
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(player))
        server.pluginManager.enablePlugin(scm)
        server.pluginManager.disablePlugin(papi)
        assertEquals("MinecraftName", PlayerUtil.getCharacterName(player))
    }

    @Test
    fun `Java static helpers and Minecraft player lookups remain available`() {
        val player = server.addPlayer("MinecraftName")
        val method = PlayerUtil::class.java.getMethod("getCharacterName", Player::class.java)
        assertEquals("MinecraftName", method.invoke(null, player))
        assertSame(player, PlayerUtil.getPlayerExact("MinecraftName"))
        assertSame(player, PlayerUtil.getPlayer("Minecraft"))
        assertNull(PlayerUtil.getPlayerExact("Minecraft"))
        assertNull(PlayerUtil.getPlayer("Missing"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["V", " ", "false", "§7[V]"])
    fun `nonempty resolved CMI vanish symbol prevents targeting in all allowed modes`(symbol: String) {
        MockBukkit.createMockPlugin("PlaceholderAPI")
        expansion("cmi", "user_vanished_symbol") { symbol }
        val player = server.addPlayer()
        for (mode in GameMode.entries) {
            player.gameMode = mode
            assertFalse(PlayerUtil.canTarget(player, true), mode.toString())
            assertFalse(PlayerUtil.canTarget(player), mode.toString())
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["%cmi_user_vanished_symbol%"])
    fun `empty or unresolved CMI vanish symbol permits survival`(symbol: String?) {
        MockBukkit.createMockPlugin("PlaceholderAPI")
        expansion("cmi", "user_vanished_symbol") { symbol }
        assertTrue(PlayerUtil.canTarget(server.addPlayer()))
    }

    @Test
    fun `missing CMI expansion or disabled PAPI permits survival`() {
        val papi = MockBukkit.createMockPlugin("PlaceholderAPI")
        val player = server.addPlayer()
        assertTrue(PlayerUtil.canTarget(player))
        expansion("cmi", "user_vanished_symbol") { "V" }
        assertFalse(PlayerUtil.canTarget(player))
        server.pluginManager.disablePlugin(papi)
        assertTrue(PlayerUtil.canTarget(player))
    }

    @Test
    fun `target game modes default to excluding creative with explicit opt in`() {
        val player = server.addPlayer()
        val default = PlayerUtil::class.java.getMethod("canTarget", Player::class.java)
        val explicit = PlayerUtil::class.java.getMethod("canTarget", Player::class.java, Boolean::class.javaPrimitiveType)
        for (mode in GameMode.entries) {
            player.gameMode = mode
            val ordinary = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE
            assertEquals(ordinary, default.invoke(null, player), "default $mode")
            assertEquals(ordinary, explicit.invoke(null, player, false), "false $mode")
            assertEquals(mode != GameMode.SPECTATOR, explicit.invoke(null, player, true), "true $mode")
        }
    }
}
