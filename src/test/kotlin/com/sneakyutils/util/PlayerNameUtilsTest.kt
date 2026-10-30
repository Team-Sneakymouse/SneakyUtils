package com.sneakyutils.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockbukkit.mockbukkit.MockBukkit
import net.sneakycharactermanager.paper.handlers.character.Character as ScmCharacter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier

class PlayerNameUtilsTest {
    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock

    @BeforeEach
    fun setUp() { server = MockBukkit.mock() }

    @AfterEach
    fun tearDown() {
        ScmCharacter.characters.clear()
        ScmCharacter.failNameLookup = false
        MockBukkit.unmock()
    }

    @Test
    fun `incompatible manager falls back then recovers`() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] =
            ScmCharacter("Momo")
        ScmCharacter.failNameLookup = true
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
        ScmCharacter.failNameLookup = false
        assertEquals("Momo", PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `enabled manager returns unformatted character name`() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] =
            ScmCharacter("Lady Momo")
        assertEquals("Lady Momo", PlayerNameUtils.getCharacterName(player))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "\t\n"])
    fun `unavailable character name returns Minecraft name`(name: String?) {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] =
            ScmCharacter(name)
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "§a Lady Momo §R", "&A Lady Momo &r",
        "&#a1B2c3 Lady Momo ", "§#A1b2C3 Lady Momo ",
        "&x&A&1&b&2&C&3 Lady Momo ", "§X§a§1§B§2§c§3 Lady Momo "
    ])
    fun `remaining format codes are removed without trimming`(name: String) {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] = ScmCharacter(name)
        assertEquals(" Lady Momo ", PlayerNameUtils.getCharacterName(player))
    }

    @ParameterizedTest
    @ValueSource(strings = ["§a§R", "&A&r", "&#a1B2c3", "§#A1b2C3", "&x&A&1&b&2&C&3", "§X§a§1§B§2§c§3", " §L\t§r ", "<red>&x&F&F&A&A&0&0</red>"])
    fun `formatting only name falls back after sanitization`(name: String) {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] = ScmCharacter(name)
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
    }

    @ParameterizedTest
    @ValueSource(strings = ["  Lady Momo  ", "Momo & Zoe §z", "&#12 §#xyz", "&xMomo", "&x", "%player_name%", "Momo <unfinished", "R&Z #a1b2c3 & §"])
    fun `nonformat literal text is preserved`(name: String) {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] = ScmCharacter(name)
        assertEquals(name, PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `expanded hex survives SCM preprocessing regression`() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        val character = ScmCharacter("&x&F&F&A&A&0&0Momo")
        ScmCharacter.characters[player] = character
        assertEquals("&x&F&F&A&A&0&0Momo", character.getName())
        assertEquals("&xMomo", character.getNameUnformatted())
        assertEquals("Momo", PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `angle tags are stripped with SCM semantics`() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] = ScmCharacter("<literal> %player_name% <name>")
        assertEquals(" %player_name% ", PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `disabled manager ignores mapped character`() {
        val manager = MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("MinecraftName")
        ScmCharacter.characters[player] = ScmCharacter("Momo")
        server.pluginManager.disablePlugin(manager)
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `missing character returns Minecraft name`() {
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(server.addPlayer("MinecraftName")))
    }

    @Test
    fun `lookup observes late manager enable and character changes`() {
        val player = server.addPlayer("MinecraftName")
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        ScmCharacter.characters[player] = ScmCharacter("First")
        assertEquals("First", PlayerNameUtils.getCharacterName(player))
        ScmCharacter.characters[player] = ScmCharacter(" Second ")
        assertEquals(" Second ", PlayerNameUtils.getCharacterName(player))
    }

    @Test
    fun `Java callers can invoke static helper`() {
        val method = PlayerNameUtils::class.java.getMethod("getCharacterName", org.bukkit.entity.Player::class.java)
        org.junit.jupiter.api.Assertions.assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals("MinecraftName", method.invoke(null, server.addPlayer("MinecraftName")))
    }

    @Test
    fun `missing manager returns Minecraft name`() {
        val player = server.addPlayer("MinecraftName")
        assertEquals("MinecraftName", PlayerNameUtils.getCharacterName(player))
    }
}
