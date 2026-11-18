package com.sneakyutils.util

import com.Zrips.CMI.events.CMIEvent
import com.Zrips.CMI.events.CMIPlayerUnVanishEvent
import com.Zrips.CMI.events.CMIPlayerVanishEvent
import com.sneakyutils.SneakyUtils
import com.sneakyutils.fixture.server.PapiServer
import com.sneakyutils.signals.DutyChangedEvent
import com.sneakyutils.util.PlayerCheckCache.Check
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager
import net.sneakycharactermanager.paper.handlers.character.CharacterLoadedEvent
import net.sneakycharactermanager.paper.handlers.character.CharacterNickChangeEvent
import net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent
import org.bukkit.GameMode
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.nio.file.Path

/** PlayerUtil's cached checks with SneakyUtils enabled; parses are counted inside real PAPI test expansions. */
class PlayerCheckCacheTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: PapiServer
    private lateinit var singleton: MockedStatic<PlaceholderAPIPlugin>
    private lateinit var papi: org.bukkit.plugin.Plugin
    private lateinit var cache: PlayerCheckCache

    private var vanish: String? = ""
    private var duty = "false"
    private var characterId: String? = "id-1"
    private var name: String? = "Momo"
    private val parses = HashMap<String, Int>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock(PapiServer())
        val plugin = mock(PlaceholderAPIPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(directory.toFile())
        `when`(plugin.logger).thenReturn(java.util.logging.Logger.getLogger("papi-test"))
        `when`(plugin.placeholderAPIConfig).thenReturn(mock(PlaceholderAPIConfig::class.java))
        val manager = LocalExpansionManager(plugin)
        `when`(plugin.localExpansionManager).thenReturn(manager)
        singleton = mockStatic(PlaceholderAPIPlugin::class.java)
        singleton.`when`<PlaceholderAPIPlugin> { PlaceholderAPIPlugin.getInstance() }.thenReturn(plugin)
        papi = MockBukkit.createMockPlugin("PlaceholderAPI")
        expansion("cmi", mapOf("user_vanished_symbol" to { vanish }))
        expansion("sneakydispatch", mapOf("on_duty" to { duty }))
        expansion(
            "sneakycharacters",
            mapOf("character_uuid" to { characterId }, "character_name_noformat" to { name }),
        )
    }

    @AfterEach
    fun tearDown() {
        singleton.close()
        MockBukkit.unmock()
        assertNull(PlayerCheckCache.active, "cache dropped on disable")
    }

    private fun expansion(identifier: String, results: Map<String, () -> String?>) {
        val expansion = object : PlaceholderExpansion() {
            override fun getIdentifier() = identifier
            override fun getAuthor() = "test"
            override fun getVersion() = "1"
            override fun onPlaceholderRequest(player: Player?, params: String): String? {
                val result = results[params] ?: return null
                parses.merge("${identifier}_$params", 1, Int::plus)
                return result()
            }
        }
        assertTrue(expansion.register())
    }

    private fun enableSneakyUtils(): SneakyUtils {
        val plugin = MockBukkit.load(SneakyUtils::class.java)
        cache = PlayerCheckCache.active!!
        return plugin
    }

    private fun vanishParses() = parses["cmi_user_vanished_symbol"] ?: 0
    private fun dutyParses() = parses["sneakydispatch_on_duty"] ?: 0
    private fun idParses() = parses["sneakycharacters_character_uuid"] ?: 0
    private fun nameParses() = parses["sneakycharacters_character_name_noformat"] ?: 0

    @Test
    fun `default config caches each check for 20 ticks`() {
        enableSneakyUtils()
        for (check in Check.entries) assertTrue(cache.enabled(check), check.toString())
        val player = server.addPlayer()
        assertTrue(PlayerUtil.canTarget(player))
        vanish = "V"
        server.scheduler.performTicks(19)
        assertTrue(PlayerUtil.canTarget(player), "still cached on the 19th tick")
        assertEquals(1, vanishParses())
        server.scheduler.performTicks(1)
        assertFalse(PlayerUtil.canTarget(player), "expired after 20 ticks")
        assertEquals(2, vanishParses())
    }

    @Test
    fun `hits equal the uncached result and parse once per window per check`() {
        enableSneakyUtils()
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("Account")
        for (state in listOf("", "V")) {
            vanish = state
            duty = if (state.isEmpty()) "false" else "TRUE"
            characterId = if (state.isEmpty()) null else "id-2"
            cache.invalidate(null, PlayerCheckCache.ALL)
            parses.clear()
            val expected = listOf(state.isEmpty(), !state.isEmpty(), characterId, "Momo")
            repeat(5) {
                assertEquals(
                    expected,
                    listOf(
                        PlayerUtil.canTarget(player),
                        PlayerUtil.onDuty(player),
                        PlayerUtil.characterId(player),
                        PlayerUtil.getCharacterName(player),
                    ),
                )
            }
            assertEquals(listOf(1, 1, 1, 1), listOf(vanishParses(), dutyParses(), idParses(), nameParses()))
        }
        val other = server.addPlayer()
        PlayerUtil.canTarget(other)
        assertEquals(2, vanishParses(), "cached per player")
    }

    @Test
    fun `zero ticks disables a cache and negative config values turn it off`() {
        enableSneakyUtils()
        val config = YamlConfiguration()
        config.set("player-util.vanish-cache-ticks", 0)
        config.set("player-util.duty-cache-ticks", -5)
        config.set("player-util.character-cache-ticks", 7)
        cache.configure(config)
        assertFalse(cache.enabled(Check.VANISH))
        assertFalse(cache.enabled(Check.DUTY))
        assertTrue(cache.enabled(Check.CHARACTER_ID))
        assertTrue(cache.enabled(Check.CHARACTER_NAME))
        val player = server.addPlayer()
        repeat(3) { PlayerUtil.canTarget(player); PlayerUtil.onDuty(player) }
        assertEquals(3, vanishParses())
        assertEquals(3, dutyParses())
        vanish = "V"
        assertFalse(PlayerUtil.canTarget(player), "every call sees the current value")
    }

    @Test
    fun `game mode is read on every call and never cached`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        assertTrue(PlayerUtil.canTarget(player))
        player.gameMode = GameMode.SPECTATOR
        assertFalse(PlayerUtil.canTarget(player, true))
        player.gameMode = GameMode.CREATIVE
        assertFalse(PlayerUtil.canTarget(player))
        assertTrue(PlayerUtil.canTarget(player, true))
        player.gameMode = GameMode.ADVENTURE
        assertTrue(PlayerUtil.canTarget(player))
        assertEquals(1, vanishParses())
    }

    @Test
    fun `CMI vanish events invalidate now and next tick, whether CMI enables before or after`() {
        val cmi = MockBukkit.createMockPlugin("CMI")
        enableSneakyUtils()
        checkCmiInvalidation()
        server.pluginManager.disablePlugin(cmi)
        assertFalse(CMIEvent.getHandlerList().registeredListeners.any { it.plugin.name == "SneakyUtils" })
        // Enabled again after SneakyUtils.
        server.pluginManager.enablePlugin(cmi)
        server.scheduler.performOneTick() // past the disable's tick, so vanish is cached again
        checkCmiInvalidation()
    }

    @Test
    fun `CMI enabling late is hooked`() {
        enableSneakyUtils()
        assertTrue(CMIEvent.getHandlerList().registeredListeners.isEmpty())
        MockBukkit.createMockPlugin("CMI")
        checkCmiInvalidation()
    }

    private fun checkCmiInvalidation() {
        val player = server.addPlayer()
        vanish = ""
        assertTrue(PlayerUtil.canTarget(player))
        vanish = "V"
        // Another CMI event on the shared handler list leaves the entry alone.
        server.pluginManager.callEvent(CMIEvent())
        assertTrue(PlayerUtil.canTarget(player))
        server.pluginManager.callEvent(CMIPlayerVanishEvent(player))
        assertFalse(PlayerUtil.canTarget(player), "dropped at the event")

        // The event fires before CMI changes state: a read in between is dropped again next tick.
        server.pluginManager.callEvent(CMIPlayerUnVanishEvent(player))
        assertFalse(PlayerUtil.canTarget(player))
        vanish = ""
        server.scheduler.performOneTick()
        assertTrue(PlayerUtil.canTarget(player), "dropped again on the next tick")
    }

    @Test
    fun `a read during a pre-change event is not cached for the rest of the tick`() {
        MockBukkit.createMockPlugin("CMI")
        enableSneakyUtils()
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("Account")
        val other = server.addPlayer("Other")

        // CMI: a listener reads during the event, then CMI changes the state in the same tick.
        assertTrue(PlayerUtil.canTarget(player))
        assertTrue(PlayerUtil.canTarget(other))
        server.pluginManager.callEvent(CMIPlayerVanishEvent(player))
        assertTrue(PlayerUtil.canTarget(player), "read during the event: state not changed yet")
        assertTrue(PlayerUtil.canTarget(other))
        vanish = "V"
        assertFalse(PlayerUtil.canTarget(player), "same tick, after the change")
        assertFalse(PlayerUtil.canTarget(other), "CMI events cover every player")

        // SCM rename.
        assertEquals("Momo", PlayerUtil.getCharacterName(player))
        server.pluginManager.callEvent(CharacterNickChangeEvent(player))
        assertEquals("Momo", PlayerUtil.getCharacterName(player))
        name = "Renamed"
        assertEquals("Renamed", PlayerUtil.getCharacterName(player))

        // SCM load: id, name and duty.
        duty = "true"
        assertEquals("id-1", PlayerUtil.characterId(player))
        assertTrue(PlayerUtil.onDuty(player))
        server.pluginManager.callEvent(LoadCharacterEvent(player))
        assertEquals("id-1", PlayerUtil.characterId(player))
        assertTrue(PlayerUtil.onDuty(player))
        characterId = "id-2"
        duty = "false"
        assertEquals("id-2", PlayerUtil.characterId(player))
        assertFalse(PlayerUtil.onDuty(player))

        // DutyChangedEvent.
        server.pluginManager.callEvent(DutyChangedEvent(player, true, "paladin"))
        assertFalse(PlayerUtil.onDuty(player))
        duty = "true"
        assertTrue(PlayerUtil.onDuty(player))

        // From the next tick on, caching resumes.
        server.scheduler.performOneTick()
        parses.clear()
        repeat(3) { PlayerUtil.canTarget(player); PlayerUtil.onDuty(player); PlayerUtil.getCharacterName(player) }
        assertEquals(listOf(1, 1, 1), listOf(vanishParses(), dutyParses(), nameParses()))
    }

    @Test
    fun `a character swap or SCM enable and disable drops duty`() {
        enableSneakyUtils()
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer()
        duty = "true"
        assertTrue(PlayerUtil.onDuty(player))
        duty = "false"
        // A swap fires only CharacterLoadedEvent (after the change).
        server.pluginManager.callEvent(CharacterLoadedEvent(player))
        assertFalse(PlayerUtil.onDuty(player))
        duty = "true"
        server.pluginManager.callEvent(LoadCharacterEvent(player))
        server.scheduler.performOneTick()
        assertTrue(PlayerUtil.onDuty(player), "load drops duty")
        duty = "false"
        server.pluginManager.disablePlugin(scm)
        assertFalse(PlayerUtil.onDuty(player), "SCM disable drops duty")
        duty = "true"
        server.pluginManager.enablePlugin(scm)
        assertTrue(PlayerUtil.onDuty(player), "SCM enable drops duty")
    }

    @Test
    fun `a swap's first event already shows both bodies fresh, from LOWEST on`() {
        enableSneakyUtils()
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        val first = server.addPlayer("First")
        val second = server.addPlayer("Second")
        characterId = "old"
        duty = "true"
        assertEquals("old", PlayerUtil.characterId(first))
        assertEquals("old", PlayerUtil.characterId(second))
        assertTrue(PlayerUtil.onDuty(second))

        // A consumer listening at LOWEST (registered after SneakyUtils' hook) and at NORMAL to the first swap event.
        val seen = ArrayList<String?>()
        val consumer = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun lowest(event: CharacterLoadedEvent) {
                seen += PlayerUtil.characterId(event.player)
                seen += PlayerUtil.characterId(second)
                seen += PlayerUtil.onDuty(second).toString()
            }

            @EventHandler(priority = EventPriority.NORMAL)
            fun normal(event: CharacterLoadedEvent) {
                seen += PlayerUtil.characterId(second)
            }
        }
        server.pluginManager.registerEvents(consumer, scm)
        // SCM has already moved both bodies when it delivers the first swap event.
        characterId = "new"
        duty = "false"
        server.pluginManager.callEvent(CharacterLoadedEvent(first))
        assertEquals(listOf("new", "new", "false", "new"), seen)
        assertEquals("new", PlayerUtil.characterId(second))
    }

    @Test
    fun `a provider being disabled bypasses the cache for the rest of the tick`() {
        enableSneakyUtils()
        val dispatch = MockBukkit.createMockPlugin("SneakyDispatch")
        val player = server.addPlayer()
        duty = "true"
        assertTrue(PlayerUtil.onDuty(player))
        // Another plugin reads during the disable event, while the provider still answers.
        var duringDisable: Boolean? = null
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.NORMAL)
            fun onDisable(event: PluginDisableEvent) {
                if (event.plugin === dispatch) duringDisable = PlayerUtil.onDuty(player)
            }
        }, MockBukkit.createMockPlugin("Consumer"))
        server.pluginManager.disablePlugin(dispatch)
        assertEquals(true, duringDisable, "read after the invalidation, before teardown")
        // The provider's teardown changes its answer in the same tick.
        duty = "false"
        assertFalse(PlayerUtil.onDuty(player), "same tick, after teardown: not the value read during disable")
        server.scheduler.performOneTick()
        parses.clear()
        repeat(3) { PlayerUtil.onDuty(player) }
        assertEquals(1, dutyParses(), "caching resumes next tick")
    }

    @Test
    fun `change marks are compared by equality, so a wrapped tick counter clears them`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        val now = server.currentTick
        cache.invalidateNowAndNextTick(player.uniqueId, PlayerCheckCache.ALL)
        cache.invalidateNowAndNextTick(null, PlayerCheckCache.ALL)
        assertTrue(cache.hasMarks())
        cache.clearOldMarks(now)
        assertTrue(cache.hasMarks(), "same tick: kept")
        // The counter wrapped (Int.MAX_VALUE to Int.MIN_VALUE): the next tick is numerically smaller.
        cache.clearOldMarks(now - 1)
        assertFalse(cache.hasMarks(), "an earlier-looking tick still clears them")
        cache.invalidateNowAndNextTick(player.uniqueId, PlayerCheckCache.ALL)
        cache.clearOldMarks(Int.MIN_VALUE.let { if (it == now) it + 1 else it })
        assertFalse(cache.hasMarks())
        server.scheduler.performOneTick()
        PlayerUtil.canTarget(player)
        assertNotSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, player), "cached again")
    }

    @Test
    fun `the active cache reference is volatile`() {
        val field = SneakyUtils::class.java.classLoader.loadClass(PlayerCheckCache::class.java.name)
            .getDeclaredField("active")
        assertTrue(java.lang.reflect.Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun `CMI disable unhooks and drops vanish entries`() {
        val cmi = MockBukkit.createMockPlugin("CMI")
        enableSneakyUtils()
        val player = server.addPlayer()
        assertTrue(PlayerUtil.canTarget(player))
        server.pluginManager.disablePlugin(cmi)
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, player))
        assertTrue(CMIEvent.getHandlerList().registeredListeners.isEmpty())
    }

    @Test
    fun `DutyChangedEvent invalidates duty now and next tick`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        assertFalse(PlayerUtil.onDuty(player))
        duty = "true"
        assertFalse(PlayerUtil.onDuty(player), "cached")
        DutyChangedEvent.fire(player, true, "paladin")
        assertTrue(PlayerUtil.onDuty(player))
        DutyChangedEvent.fire(player, false, "paladin")
        assertTrue(PlayerUtil.onDuty(player))
        duty = "false"
        server.scheduler.performOneTick()
        assertFalse(PlayerUtil.onDuty(player))
    }

    @Test
    fun `SCM load and loaded events drop id and name, rename drops the name`() {
        enableSneakyUtils()
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player = server.addPlayer("Account")
        val bystander = server.addPlayer("Other")
        fun read(p: Player) = listOf(PlayerUtil.characterId(p), PlayerUtil.getCharacterName(p))
        assertEquals(listOf("id-1", "Momo"), read(player))
        assertEquals(listOf("id-1", "Momo"), read(bystander))

        characterId = "id-2"
        name = "Bobo"
        assertEquals(listOf("id-1", "Momo"), read(player), "cached")
        val load = LoadCharacterEvent(player)
        load.isCancelled = true
        server.pluginManager.callEvent(load)
        assertEquals(listOf("id-2", "Bobo"), read(player), "dropped at MONITOR, cancelled or not")
        assertEquals(listOf("id-2", "Bobo"), read(bystander), "loads drop every player (swaps change two bodies)")

        characterId = "id-3"
        name = "Coco"
        server.pluginManager.callEvent(CharacterLoadedEvent(player))
        assertEquals(listOf("id-3", "Coco"), read(player))
        server.scheduler.performOneTick() // past the load's tick, so the id is cached again
        assertEquals(listOf("id-3", "Coco"), read(player))

        characterId = "id-4"
        name = "Dodo"
        server.pluginManager.callEvent(CharacterNickChangeEvent(player))
        assertEquals(listOf("id-3", "Dodo"), read(player), "a rename keeps the character id")

        // Fired before the load: dropped again on the next tick.
        server.pluginManager.callEvent(LoadCharacterEvent(player))
        assertEquals(listOf("id-4", "Dodo"), read(player))
        characterId = "id-5"
        server.scheduler.performOneTick()
        assertEquals(listOf("id-5", "Dodo"), read(player))
    }

    @Test
    fun `characterId is null without SCM or PAPI and for blank or unresolved output`() {
        enableSneakyUtils()
        val player = server.addPlayer("Account")
        assertNull(PlayerUtil.characterId(player), "SCM absent")
        assertEquals("Account", PlayerUtil.getCharacterName(player))
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        for (output in listOf(null, "", " ", "%sneakycharacters_character_uuid%")) {
            characterId = output
            cache.invalidate(null, PlayerCheckCache.ALL)
            assertNull(PlayerUtil.characterId(player), output.toString())
        }
        characterId = "id-1"
        cache.invalidate(null, PlayerCheckCache.ALL)
        assertEquals("id-1", PlayerUtil.characterId(player))
        server.pluginManager.disablePlugin(scm)
        assertNull(PlayerUtil.characterId(player), "SCM disabled, read live")
        server.pluginManager.enablePlugin(scm)
        server.pluginManager.disablePlugin(papi)
        assertNull(PlayerUtil.characterId(player), "PAPI disabled")
        assertTrue(PlayerUtil.canTarget(player))
        assertFalse(PlayerUtil.onDuty(player))
    }

    @Test
    fun `quit drops a player's entries and offline players are not stored`() {
        enableSneakyUtils()
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        val player: PlayerMock = server.addPlayer()
        PlayerUtil.canTarget(player)
        PlayerUtil.onDuty(player)
        PlayerUtil.characterId(player)
        PlayerUtil.getCharacterName(player)
        for (check in Check.entries) assertNotSame(PlayerCheckCache.MISS, cache.lookup(check, player))
        player.disconnect()
        for (check in Check.entries) assertSame(PlayerCheckCache.MISS, cache.lookup(check, player), check.toString())
        PlayerUtil.canTarget(player)
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, player), "offline: not stored")
    }

    @Test
    fun `clearCachedChecks drops every check for one player`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        val other = server.addPlayer()
        PlayerUtil.canTarget(player)
        PlayerUtil.onDuty(player)
        PlayerUtil.canTarget(other)
        PlayerUtil.clearCachedChecks(player)
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, player))
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.DUTY, player))
        assertNotSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, other))
        val method = PlayerUtil::class.java.getMethod("clearCachedChecks", Player::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers))
        val id = PlayerUtil::class.java.getMethod("characterId", Player::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(id.modifiers))
    }

    @Test
    fun `off the server thread the cache is neither read nor written`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        vanish = "V"
        assertFalse(PlayerUtil.canTarget(player))
        // Mockito's static PAPI mock only applies to the test thread, so another thread reads "unresolved"
        // (not vanished): a true there shows it computed the value instead of reading the cached false.
        var offThread: Boolean? = null
        val thread = Thread { offThread = PlayerUtil.canTarget(player) }
        thread.start()
        thread.join()
        assertEquals(true, offThread)
        assertFalse(PlayerUtil.canTarget(player), "the main-thread entry is unchanged")
        assertEquals(1, vanishParses())
        cache.invalidate(null, PlayerCheckCache.ALL)
        val second = Thread { PlayerUtil.canTarget(player); PlayerUtil.onDuty(player) }
        second.start()
        second.join()
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.VANISH, player), "nothing stored off-thread")
        assertSame(PlayerCheckCache.MISS, cache.lookup(Check.DUTY, player), "nothing stored off-thread")
    }

    @Test
    fun `PAPI or plugin enable and disable drop the checks that read from them`() {
        enableSneakyUtils()
        val player = server.addPlayer()
        assertTrue(PlayerUtil.canTarget(player))
        vanish = "V"
        server.pluginManager.disablePlugin(papi)
        assertTrue(PlayerUtil.canTarget(player), "PAPI off: not vanished")
        server.pluginManager.enablePlugin(papi)
        assertFalse(PlayerUtil.canTarget(player), "fresh read after PAPI enables")
        assertFalse(PlayerUtil.onDuty(player))
        duty = "true"
        MockBukkit.createMockPlugin("SneakyDispatch")
        assertTrue(PlayerUtil.onDuty(player), "SneakyDispatch enabling drops duty")
    }

    @Test
    fun `missing event classes are skipped without error`() {
        enableSneakyUtils()
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        val listener = object : Listener {}
        assertFalse(cache.registerByName(scm, "net.sneakycharactermanager.paper.Missing", listener, PlayerCheckCache.ALL))
        assertFalse(cache.registerByName(scm, "java.lang.String", listener, PlayerCheckCache.ALL))
        assertTrue(cache.registerByName(scm, CharacterLoadedEvent::class.java.name, listener, PlayerCheckCache.ALL))
    }

    @Test
    fun `without SneakyUtils enabled every call reads the placeholder`() {
        val player = server.addPlayer()
        assertNull(PlayerCheckCache.active)
        repeat(3) { PlayerUtil.canTarget(player) }
        assertEquals(3, vanishParses())
    }
}
