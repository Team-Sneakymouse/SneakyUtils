package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The movement side through real Bukkit events in MockBukkit. Engine rules in detail: engine/MovementCoreTest. */
class MovementBukkitTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var nether: WorldMock
    private lateinit var triggers: Triggers
    private val log = ArrayList<String>()

    private fun recorder(name: String) = object : AreaHandler {
        override fun onEnter(player: Player, area: Area, cause: AreaCause) {
            log += "enter $name ${player.name} $cause"
        }

        override fun onExit(player: Player, area: Area, cause: AreaCause) {
            log += "exit $name ${player.name} $cause"
        }
    }

    private fun take(): List<String> = ArrayList(log).also { log.clear() }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        nether = server.addSimpleWorld("nether")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun player(name: String, x: Double, y: Double, z: Double, w: WorldMock = world): PlayerMock {
        val p = server.addPlayer(name)
        p.teleport(Location(w, x, y, z))
        return p
    }

    private fun box(x1: Int, z1: Int, x2: Int, z2: Int, w: WorldMock = world) = Area.box(w, x1, 60, z1, x2, 70, z2)

    @Test
    fun aWatchAddedInAnEarlierJoinHandlerWorks() {
        // Found live (Paper 26.2 load run): SneakyUtils starts tracking a player in its MONITOR join handler, so a
        // plugin subscribing in its own join handler (any earlier priority) got a watch that was already closed.
        val steps = ArrayList<String>()
        var watch: Subscription? = null
        var timer: PlayerTimer? = null
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onJoin(e: org.bukkit.event.player.PlayerJoinEvent) {
                watch = triggers.watchSteps(owner, e.player) { p, from, to, _ -> steps += "${p.name} ${from.x}->${to.x}" }
                timer = triggers.everyForPlayers(owner, 1) { }.also { it.add(e.player) }
            }
        }, owner)
        val p = player("early", 0.5, 64.0, 0.5)
        assertTrue(watch!!.active, "the watch is live although SneakyUtils hadn't seen the join yet")
        assertTrue(timer!!.contains(p))
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 0.5))
        assertEquals(listOf("early 0->3"), steps)
        p.disconnect()
        assertFalse(watch!!.active, "it still ends on quit")
        val offline = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        assertFalse(offline.active, "a watch for an offline player still comes back closed")
    }

    @Test
    fun serviceIsAvailableWhileEnabled() {
        assertTrue(SneakyUtils.triggersOrNull() === triggers)
        server.pluginManager.disablePlugin(server.pluginManager.getPlugin("SneakyUtils")!!)
        assertEquals(null, SneakyUtils.triggersOrNull())
        assertThrows(IllegalStateException::class.java) { SneakyUtils.getTriggers() }
    }

    @Test
    fun enterAndExitOnMoveEvents() {
        triggers.area(owner, box(10, 10, 12, 12), recorder("A"))
        val p = player("p", 0.5, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 11.5, 64.0, 11.5))
        p.simulatePlayerMove(Location(world, 11.7, 64.0, 11.2)) // same block
        p.simulatePlayerMove(Location(world, 11.5, 64.0, 13.5))
        assertEquals(listOf("enter A p MOVE", "exit A p MOVE"), take())
    }

    @Test
    fun cancelledMoveEventsAreIgnored() {
        triggers.area(owner, box(10, 10, 12, 12), recorder("A"))
        val p = player("p", 0.5, 64.0, 0.5)
        var cancel = true
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun onMove(e: PlayerMoveEvent) {
                if (cancel) e.isCancelled = true
            }
        }, owner)
        p.simulatePlayerMove(Location(world, 11.5, 64.0, 11.5))
        assertEquals(emptyList<String>(), take(), "another plugin cancelled the move: the player never got there")
        cancel = false
        p.simulatePlayerMove(Location(world, 11.5, 64.0, 11.5))
        assertEquals(listOf("enter A p MOVE"), take())
    }

    @Test
    fun teleportWorldChangeQuitDeathRespawnAndJoin() {
        triggers.area(owner, box(0, 0, 5, 5), recorder("A"))
        triggers.area(owner, box(0, 0, 5, 5, nether), recorder("N"))
        val p = player("p", 20.5, 64.0, 2.5)
        world.spawnLocation = Location(world, 2.5, 64.0, 2.5)
        p.teleport(Location(world, 2.5, 64.0, 2.5))
        p.teleport(Location(nether, 2.5, 64.0, 2.5))
        p.teleport(Location(world, 3.5, 64.0, 2.5))
        p.health = 0.0
        p.respawn()
        p.disconnect()
        assertEquals(
            listOf(
                "enter A p TELEPORT", "exit A p WORLD_CHANGE", "enter N p WORLD_CHANGE", "exit N p WORLD_CHANGE",
                "enter A p WORLD_CHANGE", "exit A p DEATH", "enter A p TELEPORT", "exit A p QUIT",
            ),
            take(),
        )
        server.addPlayer("q") // joins at the world spawn, inside A
        assertEquals(listOf("enter A q JOIN"), take())
    }

    @Test
    fun ridersMountDismountAndThePoll() {
        triggers.area(owner, box(10, 0, 20, 5), recorder("skip"))
        triggers.area(owner, box(10, 0, 20, 5), recorder("toll"), AreaOptions.builder().riders(RiderPolicy.ALLOW).build())
        val p = player("p", 0.5, 64.0, 2.5)
        val seat = world.spawn(Location(world, 0.5, 64.0, 2.5), ArmorStand::class.java)
        seat.addPassenger(p)
        assertTrue(p.isInsideVehicle)
        // Carried by the vehicle: no move event, only the 1-tick poll sees it.
        p.location = Location(world, 15.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(listOf("enter toll p VEHICLE"), take())
        p.leaveVehicle()
        server.scheduler.performOneTick() // (dis)mounts apply on the next tick, once Bukkit has made them
        assertEquals(listOf("enter skip p MOVE"), take(), "dismounting inside a SKIP area enters it on foot")
        p.simulatePlayerMove(Location(world, 30.5, 64.0, 2.5))
        assertEquals(setOf("exit skip p MOVE", "exit toll p MOVE"), take().toSet())
    }

    @Test
    fun aVehicleTeleportedWithPassengersIsSeenByThePoll() {
        triggers.area(owner, box(500, 0, 505, 5), recorder("far"), AreaOptions.builder().riders(RiderPolicy.ALLOW).build())
        val p = player("p", 0.5, 64.0, 2.5)
        world.spawn(Location(world, 0.5, 64.0, 2.5), ArmorStand::class.java).addPassenger(p)
        p.location = Location(world, 502.5, 64.0, 2.5) // moved with the vehicle, no player teleport event
        server.scheduler.performOneTick()
        assertEquals(listOf("enter far p TELEPORT"), take(), "a 500-block jump counts as a teleport")
    }

    @Test
    fun aRiderMovedByTeleportGetsPathChecksAndThePollDoesNotRepeatTheStep() {
        // Paper moves the rider of a teleport-driven mount with a PlayerTeleportEvent every tick (measured on 26.2).
        triggers.area(owner, box(20, 0, 21, 5), recorder("gate"),
            AreaOptions.builder().riders(RiderPolicy.ALLOW).passThrough(true).build())
        val p = player("p", 10.5, 64.0, 2.5)
        world.spawn(Location(world, 10.5, 64.0, 2.5), ArmorStand::class.java).addPassenger(p)
        val stepsBefore = triggers.stats().counter("steps")
        val to = Location(world, 30.5, 64.0, 2.5)
        server.pluginManager.callEvent(PlayerTeleportEvent(p, p.location, to, PlayerTeleportEvent.TeleportCause.UNKNOWN))
        p.location = to // Paper applies the destination after the event
        server.scheduler.performOneTick() // the mounted poll reads the same block
        assertEquals(listOf("enter gate p VEHICLE", "exit gate p VEHICLE"), take(), "crossed, as a vehicle")
        assertEquals(stepsBefore + 1, triggers.stats().counter("steps"), "one step for the event and the poll together")

        triggers.area(owner, box(500, 0, 505, 5), recorder("far"), AreaOptions.builder().riders(RiderPolicy.ALLOW).build())
        val far = Location(world, 502.5, 64.0, 2.5)
        server.pluginManager.callEvent(PlayerTeleportEvent(p, p.location, far, PlayerTeleportEvent.TeleportCause.UNKNOWN))
        p.location = far
        assertEquals(listOf("enter far p TELEPORT"), take(), "past the 64-block segment limit it is still a teleport")
    }

    @Test
    fun aTeleportOnFootHasNoPath() {
        triggers.area(owner, box(20, 0, 21, 5), recorder("gate"), AreaOptions.builder().passThrough(true).build())
        val p = player("p", 10.5, 64.0, 2.5)
        p.teleport(Location(world, 30.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), take())
    }

    @Test
    fun moveEventAndPollDoNotDoubleFireASeatedStep() {
        val steps = ArrayList<String>()
        val p = player("p", 0.5, 64.0, 0.5)
        triggers.watchSteps(owner, p, { _, from, to, mounted -> steps += "${from.x}->${to.x} $mounted" },
            WatchOptions.builder().onFootOnly(false).build())
        world.spawn(Location(world, 0.5, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick() // the mount applies on the next tick (a rider's next packet comes later)
        p.simulatePlayerMove(Location(world, 1.5, 64.0, 0.5))
        server.scheduler.performOneTick() // the poll reads the same block
        p.location = Location(world, 2.5, 64.0, 0.5)
        server.scheduler.performOneTick()
        p.simulatePlayerMove(Location(world, 2.6, 64.0, 0.5))
        p.leaveVehicle()
        server.scheduler.performOneTick()
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 0.5))
        assertEquals(listOf("0->1 true", "1->2 true", "2->3 false"), steps)
    }

    @Test
    fun onFootWatchesPauseWhileRiding() {
        val steps = ArrayList<Int>()
        val p = player("p", 0.5, 64.0, 0.5)
        triggers.watchSteps(owner, p) { _, _, to, _ -> steps += to.x }
        p.simulatePlayerMove(Location(world, 1.5, 64.0, 0.5))
        world.spawn(Location(world, 1.5, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 0.5))
        p.leaveVehicle()
        server.scheduler.performOneTick()
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 0.5))
        assertEquals(listOf(1, 3), steps)
    }

    @Test
    fun blockWatchReadsTheWorld() {
        world.getBlockAt(3, 64, 0).type = Material.WATER
        world.getBlockAt(4, 63, 0).type = Material.OAK_FENCE
        val p = player("p", 0.5, 64.0, 0.5)
        val seen = ArrayList<String>()
        triggers.watchBlock(owner, p, BlockOffset.FEET, setOf(Material.WATER)) { _, b, m -> seen += "feet ${b.x} ${b.type} $m" }
        triggers.watchBlockUnder(owner, p, setOf(Material.OAK_FENCE)) { _, b -> seen += "under ${b.x} ${b.type}" }
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 0.5))
        p.simulatePlayerMove(Location(world, 4.5, 64.5, 0.5))
        assertEquals(listOf("feet 3 WATER ENTERED", "feet 4 AIR LEFT", "under 4 OAK_FENCE"), seen)
    }

    @Test
    fun queuedBlockCallbackKeepsItsWorldWhenAnEarlierHandlerMovesThePlayerToAnotherWorld() {
        // Regression (Codex M2 #1): the block was looked up in the player's world at delivery, not at queueing.
        world.getBlockAt(3, 64, 0).type = Material.WATER
        val p = player("p", 0.5, 64.0, 0.5)
        val seen = ArrayList<String>()
        triggers.watchSteps(owner, p) { player, _, to, _ ->
            if (to.x == 3) player.teleport(Location(nether, 3.5, 64.0, 0.5))
        }
        triggers.watchBlock(owner, p, BlockOffset.FEET, setOf(Material.WATER)) { _, b, m -> seen += "${b.world.name} ${b.type} $m" }
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 0.5))
        assertEquals(listOf("world WATER ENTERED"), seen)
    }

    @Test
    fun watchesEndOnQuitUnlessKept() {
        val p = player("p", 0.5, 64.0, 0.5)
        val ends = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        val kept = triggers.watchSteps(owner, p, { _, _, _, _ -> }, WatchOptions.builder().keepOnQuit(true).build())
        p.disconnect()
        assertFalse(ends.active)
        assertTrue(kept.active)
        val offline = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        assertFalse(offline.active, "a watch for an offline player without keepOnQuit is born closed")
    }

    @Test
    fun ownerDisableClosesEverythingSilently() {
        val p = player("p", 2.5, 64.0, 2.5)
        val area = triggers.area(owner, box(0, 0, 5, 5), recorder("A"), AreaOptions.builder().fireOnRegister(true).build())
        val watch = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        assertEquals(listOf("enter A p REGISTER"), take())
        server.pluginManager.disablePlugin(owner)
        assertFalse(area.active)
        assertFalse(watch.active)
        assertEquals(emptyList<String>(), take(), "no exit into a disabling plugin")
        assertThrows(IllegalArgumentException::class.java) { triggers.area(owner, box(0, 0, 1, 1), recorder("B")) }
    }

    @Test
    fun closeFiresUnregisterAndIsIdempotent() {
        val p = player("p", 2.5, 64.0, 2.5)
        val area = triggers.area(owner, box(0, 0, 5, 5), recorder("A"), AreaOptions.builder().fireOnRegister(true).build())
        area.close()
        area.close()
        assertEquals(listOf("enter A p REGISTER", "exit A p UNREGISTER"), take())
        assertTrue(area.id.startsWith("Owner:area#"))
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), take())
    }

    @Test
    fun handlersMaySubscribeUnsubscribeTeleportAndKick() {
        val p = player("p", 20.5, 64.0, 2.5)
        lateinit var self: Subscription
        self = triggers.area(owner, box(0, 0, 5, 5), object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                log += "enter self $cause"
                self.close() // closes itself: its exit follows
                triggers.area(owner, box(0, 0, 5, 5), recorder("late"), AreaOptions.builder().fireOnRegister(true).build())
            }

            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                log += "exit self $cause"
            }
        })
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("enter self MOVE", "exit self UNREGISTER", "enter late p REGISTER"), take())

        triggers.area(owner, box(100, 0, 105, 5), object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                log += "enter bouncer $cause"
                // Inside a move event a direct teleport works; inside a teleport event it must wait a tick.
                if (cause == AreaCause.TELEPORT) {
                    server.scheduler.runTask(owner, Runnable { player.teleport(Location(world, 300.5, 64.0, 2.5)) })
                } else {
                    player.teleport(Location(world, 300.5, 64.0, 2.5))
                }
            }

            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                log += "exit bouncer $cause"
            }
        })
        p.teleport(Location(world, 102.5, 64.0, 2.5))
        server.scheduler.performOneTick()
        assertEquals(listOf("exit late p TELEPORT", "enter bouncer TELEPORT", "exit bouncer TELEPORT"), take())
        assertEquals(300, p.location.blockX)
        p.simulatePlayerMove(Location(world, 290.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 250.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 200.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 150.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 102.5, 64.0, 2.5))
        assertEquals(listOf("enter bouncer MOVE", "exit bouncer TELEPORT"), take())
        assertEquals(300, p.location.blockX)

        triggers.area(owner, box(200, 0, 205, 5), object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                log += "enter kicker"
                (player as PlayerMock).disconnect()
            }

            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                log += "exit kicker $cause"
            }
        })
        p.teleport(Location(world, 202.5, 64.0, 2.5))
        assertEquals(listOf("enter kicker", "exit kicker QUIT"), take())
    }

    @Test
    fun aTeleportInsideATeleportEventIsReconciledOnTheNextTick() {
        // Bukkit finishes the outer teleport after its event, undoing a teleport made inside it. The engine saw the
        // inner one; on the next tick it reconciles with where the player really is (regression, Codex M2 #3).
        var bounce = true
        triggers.area(owner, box(100, 0, 105, 5), object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                log += "enter lobby $cause"
                if (bounce) {
                    bounce = false
                    player.teleport(Location(world, 300.5, 64.0, 2.5))
                }
            }

            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                log += "exit lobby $cause"
            }
        })
        triggers.area(owner, box(300, 0, 305, 5), recorder("arena"))
        val p = player("p", 0.5, 64.0, 2.5)
        take()
        p.teleport(Location(world, 102.5, 64.0, 2.5))
        assertEquals(102, p.location.blockX, "MockBukkit, like Paper, completes the outer teleport")
        assertEquals(listOf("enter lobby TELEPORT", "exit lobby TELEPORT", "enter arena p TELEPORT"), take())
        server.scheduler.performOneTick()
        assertEquals(listOf("exit arena p TELEPORT", "enter lobby TELEPORT"), take(), "membership matches the lobby again")
        server.scheduler.performTicks(3)
        assertEquals(emptyList<String>(), take())
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner.name == "SneakyUtils" }, "the resync task is gone")
    }

    @Test
    fun anOrdinaryTeleportSchedulesNothing() {
        triggers.area(owner, box(100, 0, 105, 5), recorder("A"))
        val p = player("p", 0.5, 64.0, 2.5)
        p.teleport(Location(world, 102.5, 64.0, 2.5))
        assertEquals(listOf("enter A p TELEPORT"), take())
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner.name == "SneakyUtils" })
    }

    @Test
    fun anEnterExitTeleportLoopIsCutOffAndLoggedOnceAMinuteWithTheSubscription() {
        val su = server.pluginManager.getPlugin("SneakyUtils")!!
        val logged = ArrayList<String>()
        su.logger.addHandler(object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                if (record.message.contains("dropped")) logged += record.message
            }

            override fun flush() {}
            override fun close() {}
        })
        val service = triggers as com.sneakyutils.triggers.service.TriggerService
        var now = 1_000_000L
        service.clock = { now }
        val a = triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { p, _, _ -> p.teleport(Location(world, 102.5, 64.0, 2.5)) })
        val b = triggers.area(owner, box(100, 0, 105, 5), AreaHandler.onEnter { p, _, _ -> p.teleport(Location(world, 2.5, 64.0, 2.5)) })
        val p = player("p", 50.5, 64.0, 2.5)
        fun loop() {
            p.teleport(Location(world, 50.5, 64.0, 2.5))
            p.simulatePlayerMove(Location(world, 40.5, 64.0, 2.5))
            p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        }
        loop()
        assertEquals(1, logged.size, logged.toString())
        assertTrue(logged[0].contains(a.id) || logged[0].contains(b.id), logged[0])
        loop()
        assertEquals(1, logged.size, "rate-limited")
        now += 61_000
        loop()
        assertEquals(2, logged.size)
        assertTrue(logged[1].contains("more dropped since the last report"), logged[1])
        assertTrue(service.counters()["queue.dropped"]!! > 0)
        assertTrue(triggers.stats().warnings.any { it.contains("dropped by handler loops") })
    }

    @Test
    fun aThrowingHandlerIsIsolated() {
        var good = 0
        var badCalls = 0
        val bad = triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { _, _, _ ->
            badCalls++
            error("boom")
        })
        triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { _, _, _ -> good++ })
        val p = player("p", 20.5, 64.0, 2.5)
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(2, good)
        assertEquals(2, badCalls)
        assertTrue(bad.active, "a throwing subscription stays subscribed")
    }

    @Test
    fun apiThrowsOffTheServerThread() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Throwable?> {
                try {
                    triggers.area(owner, box(0, 0, 1, 1), recorder("A"))
                    null
                } catch (t: Throwable) {
                    t
                }
            }.get(10, TimeUnit.SECONDS)
            assertTrue(result is IllegalStateException, "got $result")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun worldUnloadClosesItsAreas() {
        val gone = server.addSimpleWorld("instance")
        val a = triggers.area(owner, box(0, 0, 5, 5, gone), recorder("I"))
        val kept = triggers.area(owner, box(0, 0, 5, 5), recorder("A"))
        assertTrue(server.unloadWorld(gone, false))
        assertFalse(a.active)
        assertTrue(kept.active)
        val p = player("p", 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p TELEPORT"), take())
        p.gameMode = GameMode.SURVIVAL
    }

    @Test
    fun spectatorsAreSkippedByDefault() {
        triggers.area(owner, box(0, 0, 5, 5), recorder("A"))
        val p = player("p", 20.5, 64.0, 2.5)
        p.gameMode = GameMode.SPECTATOR
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), take())
        p.gameMode = GameMode.SURVIVAL
        assertEquals(listOf("enter A p MOVE"), take())
    }

    @Test
    fun queuedEventsOfAnExplicitlyClosedAreaAreDroppedOnceItsOwnerDisables() {
        // Regression (Codex full review #1): the close removed B from its owner's set, so the owner's disable couldn't
        // silence it, and its queued enter and UNREGISTER exit ran inside a disabled plugin.
        val other = MockBukkit.createMockPlugin("Other")
        val p = player("p", 20.5, 64.0, 2.5)
        lateinit var b: Subscription
        triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { _, _, cause ->
            log += "enter A $cause"
            b.close()
            server.pluginManager.disablePlugin(other)
        })
        b = triggers.area(other, box(0, 0, 5, 5), recorder("B"))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("enter A MOVE"), take(), "nothing reaches the disabled owner")
    }

    @Test
    fun aCloseRejectedInsideAPredicateLeavesTheSubscriptionActiveAndClosable() {
        // Regression (Codex full review #4): close() marked the subscription closed and left the registry before the
        // engine refused, leaving an indexed area nobody could close.
        val p = player("p", 20.5, 64.0, 2.5)
        val victim = triggers.area(owner, box(0, 0, 5, 5), recorder("V"))
        val watch = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        var tries = 0
        triggers.area(owner, Area.custom(world, 0, 60, 0, 5, 70, 5) { _, _, _ ->
            tries++
            if (victim.active) runCatching { victim.close() }.onSuccess { log += "victim closed" }
            if (watch.active) runCatching { watch.close() }.onSuccess { log += "watch closed" }
            true
        }, recorder("C"))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertTrue(tries > 0)
        assertEquals(listOf("enter V p MOVE", "enter C p MOVE"), take(), "both closes were refused")
        assertTrue(victim.active)
        assertTrue(watch.active)
        victim.close()
        watch.close()
        assertFalse(victim.active)
        assertFalse(watch.active)
        assertEquals(listOf("exit V p UNREGISTER"), take())
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), take(), "the closed area is really gone")
    }

    @Test
    fun anOwnerDisabledFromInsideAPredicateStillClosesEverything() {
        val other = MockBukkit.createMockPlugin("Other")
        val p = player("p", 20.5, 64.0, 2.5)
        val area = triggers.area(other, box(0, 0, 5, 5), recorder("O"))
        val watch = triggers.watchSteps(other, p) { _, _, _, _ -> log += "step" }
        triggers.area(owner, Area.custom(world, 0, 60, 0, 5, 70, 5) { _, _, _ ->
            server.pluginManager.disablePlugin(other)
            true
        }, recorder("C"))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertFalse(area.active)
        assertFalse(watch.active)
        assertEquals(listOf("enter C p MOVE"), take(), "nothing reaches the disabled owner")
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("exit C p MOVE", "enter C p MOVE"), take())
        assertEquals(1, triggers.stats().byKind["area"], "only C is left")
    }

    @Test
    fun areaStepsTellWalkingAndTeleportingApart() {
        val steps = ArrayList<String>()
        triggers.area(owner, box(0, 0, 20, 5), object : AreaHandler {
            override fun onStep(player: Player, area: Area, cause: AreaCause) {
                steps += "step $cause"
            }
        }, AreaOptions.builder().stepEvents(true).build())
        val legacy = ArrayList<String>()
        triggers.area(owner, box(0, 0, 20, 5), object : AreaHandler {
            override fun onStep(player: Player, area: Area) {
                legacy += "step"
            }
        }, AreaOptions.builder().stepEvents(true).build())
        val p = player("p", 2.5, 64.0, 2.5)
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 2.5))
        p.teleport(Location(world, 10.5, 64.0, 2.5))
        assertEquals(listOf("step MOVE", "step TELEPORT"), steps)
        assertEquals(listOf("step", "step"), legacy, "the two-argument onStep still gets every step")
    }
}
