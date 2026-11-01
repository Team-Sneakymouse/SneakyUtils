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

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, 64)
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
}
