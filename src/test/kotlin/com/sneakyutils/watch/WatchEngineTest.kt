package com.sneakyutils.watch

import com.sneakyutils.SneakyUtils
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.MemoryConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.io.File
import java.util.UUID

class WatchEngineTest {

    /** A camera: an eye looking south. */
    data class Cam(val id: String, val eye: Location)

    class Engine(plugin: SneakyUtils, private val cams: Map<String, Cam>) : WatchEngine<Cam>(plugin, "test-sessions.yml") {
        val cfg = MemoryConfiguration().apply {
            set("watch-mode", "spectator")
            set("sleeping-body.enabled", false)
            set("hide-watcher-gear", false)
        }
        var entered = 0
        var left = 0
        override fun feed(id: String) = cams[id]
        override fun feedId(f: Cam) = f.id
        override fun lens(f: Cam): Location = f.eye.clone()
        override fun normal(f: Cam) = Vector(0, 0, 1)
        override fun limit(f: Cam) = 90.0
        override fun yaw(f: Cam) = 0f
        override fun pitch(f: Cam) = 0f
        override fun settings(): ConfigurationSection = cfg
        override fun switchOrder(p: Player, current: Cam) = cams.values.sortedBy { it.id }
        // MockBukkit's mannequins can't take a profile
        override fun spawnBody(p: Player, at: Location): Entity = at.world.spawn(at, ArmorStand::class.java)
        override fun entered(p: Player, s: WatchSession, f: Cam, first: Boolean) { entered++ }
        override fun left(p: Player, s: WatchSession) { left++ }
    }

    /** MockBukkit has no plugin chunk tickets: Paper's contract, one ticket per plugin per chunk. */
    class TicketWorld : WorldMock(Material.STONE, 64) {
        val tickets = HashMap<Long, MutableSet<Plugin>>()
        private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        override fun addPluginChunkTicket(x: Int, z: Int, plugin: Plugin) =
            tickets.getOrPut(key(x, z)) { HashSet() }.add(plugin)
        override fun removePluginChunkTicket(x: Int, z: Int, plugin: Plugin) =
            tickets[key(x, z)]?.remove(plugin) ?: false
        override fun getPluginChunkTickets(x: Int, z: Int): Collection<Plugin> = tickets[key(x, z)].orEmpty().toList()
    }

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: TicketWorld

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = TicketWorld()
        server.addWorld(world)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun watchingPutsTheEyeAtTheLensAndLeavingPutsThemBack() {
        val cam = Cam("a", Location(world, 10.5, 70.0, 10.5))
        val engine = Engine(plugin, mapOf("a" to cam))
        engine.register()
        val p = server.addPlayer()
        p.gameMode = GameMode.SURVIVAL
        p.teleport(Location(world, 0.5, 65.0, 0.5))

        engine.watch(p, cam)
        assertTrue(WatchEngine.watchingAny(p))
        assertEquals(GameMode.SPECTATOR, p.gameMode)
        assertEquals(cam.eye.y, p.location.y + p.eyeHeight, 1e-6)
        assertEquals(1, engine.entered)

        engine.leave(p, null, true)
        assertFalse(WatchEngine.watchingAny(p))
        assertEquals(GameMode.SURVIVAL, p.gameMode)
        assertEquals(0.5, p.location.x, 1e-6)
        assertEquals(65.0, p.location.y, 1e-6)
        assertEquals(1, engine.left)
        engine.shutdown()
    }

    @Test
    fun aPlayerWatchesThroughOneEngineAtATime() {
        val cam = Cam("a", Location(world, 10.5, 70.0, 10.5))
        val one = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val two = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val p = server.addPlayer()

        one.watch(p, cam)
        two.watch(p, cam)
        assertTrue(one.sessions().containsKey(p.uniqueId))
        assertNull(two.sessions()[p.uniqueId])
        one.shutdown()
        two.shutdown()
        assertFalse(WatchEngine.watchingAny(p))
    }

    @Test
    fun aGoneFeedSendsTheWatcherBack() {
        val cams = mutableMapOf("a" to Cam("a", Location(world, 10.5, 70.0, 10.5)))
        val engine = Engine(plugin, cams).also { it.register() }
        val p = server.addPlayer()
        engine.watch(p, cams.getValue("a"))
        cams.clear()
        engine.tickSessions()
        assertFalse(engine.sessions().containsKey(p.uniqueId))
        engine.shutdown()
    }

    @Test
    fun aBodyKeepsItsChunkLoadedUntilTheLastWatcherThereLeaves() {
        val cam = Cam("a", Location(world, 100.5, 70.0, 100.5))
        val one = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val two = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val a = server.addPlayer().also { it.teleport(Location(world, 1.5, 65.0, 1.5)) }
        val b = server.addPlayer().also { it.teleport(Location(world, 3.5, 65.0, 3.5)) }

        one.watch(a, cam)
        two.watch(b, cam)
        assertTrue(plugin in world.getPluginChunkTickets(0, 0))
        one.leave(a, null, true)
        assertTrue(plugin in world.getPluginChunkTickets(0, 0), "b's body is still in the chunk")
        two.leave(b, null, true)
        assertFalse(plugin in world.getPluginChunkTickets(0, 0))
        one.shutdown()
        two.shutdown()
    }

    @Test
    fun anotherWatchKeepsTheRecordsOfPlayersNotYetPutBack() {
        val file = File(plugin.dataFolder, "test-sessions.yml")
        val away = UUID.randomUUID()
        plugin.dataFolder.mkdirs()
        YamlConfiguration().apply {
            set("watching.$away.world", world.name)
            set("watching.$away.x", 5.5)
            set("watching.$away.y", 66.0)
            set("watching.$away.z", 7.5)
            set("watching.$away.mode", "CREATIVE")
        }.save(file)
        val cam = Cam("a", Location(world, 10.5, 70.0, 10.5))
        val engine = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val p = server.addPlayer()

        engine.watch(p, cam)
        engine.leave(p, null, true)
        val saved = YamlConfiguration.loadConfiguration(file)
        assertTrue(saved.isConfigurationSection("watching.$away"), "the crashed watcher's record is kept")
        assertFalse(saved.isConfigurationSection("watching.${p.uniqueId}"))

        val back = org.mockbukkit.mockbukkit.entity.PlayerMock(server, "Away", away).also { server.addPlayer(it) }
        engine.restoreSaved(back)
        assertEquals(GameMode.CREATIVE, back.gameMode)
        assertEquals(5.5, back.location.x, 1e-6)
        assertFalse(YamlConfiguration.loadConfiguration(file).isConfigurationSection("watching.$away"))
        engine.shutdown()
    }

    @Test
    fun aRecordStaysUntilThePlayerIsReallyPutBack() {
        val file = File(plugin.dataFolder, "test-sessions.yml")
        val away = UUID.randomUUID()
        plugin.dataFolder.mkdirs()
        YamlConfiguration().apply {
            set("watching.$away.world", "not-loaded")
            set("watching.$away.x", 5.5)
            set("watching.$away.y", 66.0)
            set("watching.$away.z", 7.5)
        }.save(file)
        val cam = Cam("a", Location(world, 10.5, 70.0, 10.5))
        val engine = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val back = org.mockbukkit.mockbukkit.entity.PlayerMock(server, "Away", away).also { server.addPlayer(it) }
        engine.restoreSaved(back)
        engine.saveSessions()
        assertTrue(YamlConfiguration.loadConfiguration(file).isConfigurationSection("watching.$away"))
        engine.watch(back, cam)                       // not while still waiting to be put back
        assertFalse(engine.sessions().containsKey(away))
        assertEquals(66.0, YamlConfiguration.loadConfiguration(file).getDouble("watching.$away.y"), 1e-6)
        engine.shutdown()
    }

    @Test
    fun aRefusedReturnKeepsTheRecordUntilThePlayerIsReallyPutBack() {
        val file = File(plugin.dataFolder, "test-sessions.yml")
        val cam = Cam("a", Location(world, 10.5, 70.0, 10.5))
        val engine = Engine(plugin, mapOf("a" to cam)).also { it.register() }
        val p = server.addPlayer()
        p.gameMode = GameMode.SURVIVAL
        p.teleport(Location(world, 0.5, 65.0, 0.5))
        engine.watch(p, cam)

        // Another plugin refuses the teleport back into the body.
        var refuse = true
        val blocker = object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
            fun onTeleport(e: org.bukkit.event.player.PlayerTeleportEvent) {
                if (refuse) e.isCancelled = true
            }
        }
        server.pluginManager.registerEvents(blocker, plugin)
        engine.leave(p, null, true)
        assertFalse(WatchEngine.watchingAny(p))
        assertTrue(p.location.y > 66.0, "still at the camera")
        val saved = YamlConfiguration.loadConfiguration(file)
        assertTrue(saved.isConfigurationSection("watching.${p.uniqueId}"), "the way back is kept")
        assertEquals(65.0, saved.getDouble("watching.${p.uniqueId}.y"), 1e-6)

        // Next time it can be done (their next join), they are put back and the record goes.
        refuse = false
        engine.restoreSaved(p)
        assertEquals(0.5, p.location.x, 1e-6)
        assertEquals(65.0, p.location.y, 1e-6)
        assertEquals(GameMode.SURVIVAL, p.gameMode)
        assertFalse(YamlConfiguration.loadConfiguration(file).isConfigurationSection("watching.${p.uniqueId}"))
        engine.shutdown()
    }
}
