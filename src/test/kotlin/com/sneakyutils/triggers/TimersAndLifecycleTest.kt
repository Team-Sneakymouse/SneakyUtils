package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.Location
import org.bukkit.entity.Player
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
import org.mockbukkit.mockbukkit.world.WorldMock
import java.lang.management.ManagementFactory
import java.util.logging.Handler
import java.util.logging.LogRecord

class TimersAndLifecycleTest {
    private lateinit var server: ServerMock
    private lateinit var su: SneakyUtils
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers
    private val service get() = triggers as TriggerService

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        su = MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun suTasks() = server.scheduler.pendingTasks.count { it.owner === su }

    // ---------------------------------------------------------------- global timers

    @Test
    fun timersWithTheSameIntervalAreSpreadOverItsTicks() {
        val runsByTick = HashMap<Long, Int>()
        repeat(20) { triggers.every(owner, 20) { runsByTick.merge(service.ticks, 1, Int::plus) } }
        server.scheduler.performTicks(40)
        assertEquals(40, runsByTick.values.sum(), "each of 20 timers ran twice in 40 ticks")
        assertTrue(runsByTick.values.all { it == 1 }, "never two on the same tick: $runsByTick")
    }

    @Test
    fun firstRunWithinOneIntervalAndNoCatchUp() {
        var runs = 0
        triggers.every(owner, 5) { runs++ }
        server.scheduler.performTicks(5)
        assertEquals(1, runs)
        // Second and third passes on the same tick (as if the server were behind) run nothing.
        service.runTimers()
        service.runTimers()
        assertEquals(1, runs)
        server.scheduler.performTicks(10)
        assertEquals(3, runs)
    }

    @Test
    fun aTimerRegisteredByATimerCallbackStartsNextTick() {
        // Regression (Codex M3): player timers were read after the global callbacks ran.
        server.addPlayer("a")
        server.addPlayer("b")
        var calls = 0
        var created = false
        triggers.every(owner, 1) {
            if (!created) {
                created = true
                triggers.everyForPlayers(owner, 1, { calls++ }, PlayerScope.ALL_ONLINE)
            }
        }
        server.scheduler.performOneTick() // the global timer runs and registers the player timer
        assertTrue(created)
        assertEquals(0, calls, "not in the tick it was registered")
        server.scheduler.performOneTick()
        assertEquals(2, calls)
    }

    @Test
    fun intervalBounds() {
        assertThrows(IllegalArgumentException::class.java) { triggers.every(owner, 0) {} }
        assertThrows(IllegalArgumentException::class.java) { triggers.every(owner, 72_001) {} }
        assertThrows(IllegalArgumentException::class.java) { triggers.everyForPlayers(owner, -1, { }) }
        triggers.every(owner, 72_000) {}
        triggers.everyForPlayers(owner, 72_000, { })
    }

    @Test
    fun tickCounterPastIntRangeKeepsTheCadence() {
        service.ticks = Int.MAX_VALUE.toLong() - 3
        var runs = 0
        triggers.every(owner, 2) { runs++ }
        server.scheduler.performTicks(10)
        assertEquals(5, runs)
        assertTrue(service.ticks > Int.MAX_VALUE)
    }

    @Test
    fun tickerRunsOnlyWhileThereIsWork() {
        assertEquals(0, suTasks(), "idle: no ticker")
        val t = triggers.every(owner, 3) {}
        assertEquals(1, suTasks())
        t.close()
        server.scheduler.performOneTick()
        assertEquals(0, suTasks(), "stopped itself once idle")
    }

    // ---------------------------------------------------------------- per-player timers

    @Test
    fun perPlayerTimerSpreadsPlayersOverTheInterval() {
        val calls = ArrayList<Pair<Long, String>>()
        val timer = triggers.everyForPlayers(owner, 4, { p -> calls += service.ticks to p.name })
        val players = (0 until 8).map { server.addPlayer("p$it") }
        for (p in players) timer.add(p)
        timer.add(players[0]) // already in: ignored
        assertEquals(8, timer.size)
        server.scheduler.performTicks(4)
        assertEquals(8, calls.size)
        assertEquals(players.map { it.name }.toSet(), calls.map { it.second }.toSet())
        assertTrue(calls.groupBy { it.first }.values.all { it.size == 2 }, "two players a tick: $calls")
    }

    @Test
    fun playersLeaveOnQuitAndRemove() {
        var calls = 0
        val timer = triggers.everyForPlayers(owner, 1, { calls++ })
        val a = server.addPlayer("a")
        val b = server.addPlayer("b")
        timer.add(a)
        timer.add(b)
        server.scheduler.performOneTick()
        assertEquals(2, calls)
        a.disconnect()
        timer.remove(b)
        assertFalse(timer.contains(a))
        assertEquals(0, timer.size)
        server.scheduler.performOneTick()
        assertEquals(2, calls)
        timer.add(a) // offline: ignored
        assertEquals(0, timer.size)
    }

    @Test
    fun deadPlayersArePausedFromDeathToRespawnUnlessAsked() {
        // MagicSpells took players out of every `ticks` set on death and back on respawn; 116 passives rely on it.
        val calls = HashMap<String, Int>()
        val all = triggers.everyForPlayers(owner, 1, { p -> calls.merge("all ${p.name}", 1, Int::plus) }, PlayerScope.ALL_ONLINE)
        val added = triggers.everyForPlayers(owner, 1, { p -> calls.merge("added ${p.name}", 1, Int::plus) })
        val alsoDead = triggers.everyForPlayers(owner, 1, { p -> calls.merge("dead too ${p.name}", 1, Int::plus) },
            PlayerTimerOptions.builder().skipDead(false).build())
        assertTrue(PlayerTimerOptions.DEFAULT.skipDead)
        assertEquals(PlayerScope.ADDED, PlayerTimerOptions.DEFAULT.scope)
        val a = server.addPlayer("a")
        added.add(a)
        alsoDead.add(a)
        server.scheduler.performOneTick()
        a.health = 0.0
        assertTrue(a.isDead)
        server.scheduler.performTicks(3)
        assertTrue(all.contains(a) && added.contains(a), "a paused player is still in the timer")
        assertEquals(1, all.size)
        a.respawn()
        server.scheduler.performOneTick()
        assertEquals(mapOf("all a" to 2, "added a" to 2, "dead too a" to 5), calls)
        // Added while dead: paused until the respawn.
        calls.clear()
        a.health = 0.0
        added.remove(a)
        added.add(a)
        server.scheduler.performOneTick()
        assertEquals(null, calls["added a"])
        a.respawn()
        server.scheduler.performOneTick()
        assertEquals(1, calls["added a"])
        // Quitting while dead leaves nothing behind.
        a.health = 0.0
        a.disconnect()
        assertEquals(0, all.size + added.size + alsoDead.size)
    }

    @Test
    fun allOnlineTracksJoinsAndIsFlagged() {
        server.addPlayer("early")
        val timer = triggers.everyForPlayers(owner, 2, { }, PlayerScope.ALL_ONLINE)
        assertEquals(1, timer.size)
        val late = server.addPlayer("late")
        assertEquals(2, timer.size)
        late.disconnect()
        assertEquals(1, timer.size)
        val stats = triggers.stats()
        assertEquals(1, stats.allOnlineTimers)
        assertEquals(1, stats.count("player-timer-all-online"))
    }

    @Test
    fun intervalOneWithAThousandPlayersCallsEveryoneEveryTickWithoutAllocating() {
        var calls = 0L
        val timer = triggers.everyForPlayers(owner, 1, { calls++ })
        repeat(1000) { timer.add(server.addPlayer("p$it")) }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        // Drive the timer loop as the ticker does: advance the tick counter, then run.
        repeat(200) { service.ticks++; service.runTimers() } // warm up
        calls = 0
        val before = threads.getThreadAllocatedBytes(id)
        repeat(100) { service.ticks++; service.runTimers() }
        val bytes = threads.getThreadAllocatedBytes(id) - before
        assertEquals(100_000L, calls)
        assertTrue(bytes < 16 * 1024, "100 ticks x 1000 players allocated $bytes bytes")
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun errorsAreCountedAndLoggedAtMostOnceAMinute() {
        val records = ArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.message.contains("threw")) records += record
            }

            override fun flush() {}
            override fun close() {}
        }
        su.logger.addHandler(handler)
        try {
            var now = 1_000_000L
            service.clock = { now }
            val sub = triggers.every(owner, 1) { error("boom") }
            var ok = 0
            triggers.every(owner, 1) { ok++ }
            server.scheduler.performTicks(100)
            assertEquals(1, records.size)
            assertTrue(records[0].message.contains(sub.id))
            assertTrue(records[0].message.contains("Owner"))
            assertTrue(records[0].thrown is IllegalStateException, "stack trace attached")
            now += 61_000
            server.scheduler.performOneTick()
            assertEquals(2, records.size)
            assertTrue(records[1].message.contains("99 more"), records[1].message)
            assertEquals(101, ok, "the other timer kept running")
            assertTrue(sub.active)
            val stats = triggers.stats()
            assertEquals(101L, stats.subscriptions.single { it.id == sub.id }.errors)
            assertEquals(101L, stats.counter("handler.errors"))
        } finally {
            su.logger.removeHandler(handler)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun ownerDisableClosesTimersAndPlayerTimers() {
        var runs = 0
        val t = triggers.every(owner, 1) { runs++ }
        val pt = triggers.everyForPlayers(owner, 1, { runs++ })
        pt.add(server.addPlayer())
        server.pluginManager.disablePlugin(owner)
        assertFalse(t.active)
        assertFalse(pt.active)
        server.scheduler.performTicks(3)
        assertEquals(0, runs)
        assertEquals(0, suTasks())
    }

    @Test
    fun aPluginDisablingDuringDispatchStopsItsOwnQueuedCalls() {
        val other = MockBukkit.createMockPlugin("Other")
        val log = ArrayList<String>()
        triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ ->
            log += "first"
            server.pluginManager.disablePlugin(owner)
        })
        triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> log += "second" })
        triggers.area(other, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> log += "other" })
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("first", "other"), log)
    }

    @Test
    fun sneakyUtilsDisableClosesEverythingWithoutExits() {
        val log = ArrayList<String>()
        val p = server.addPlayer()
        p.teleport(Location(world, 2.5, 64.0, 2.5))
        val area = triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), object : AreaHandler {
            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                log += "exit $cause"
            }
        }, AreaOptions.builder().fireOnRegister(true).build())
        val timer = triggers.every(owner, 1) {}
        val watch = triggers.watchSteps(owner, p) { _, _, _, _ -> }
        server.pluginManager.disablePlugin(su)
        assertEquals(emptyList<String>(), log)
        assertFalse(area.active)
        assertFalse(timer.active)
        assertFalse(watch.active)
        area.close() // idempotent after shutdown
    }

    @Test
    fun statsCountByKindAndOwnerAndExposeCounters() {
        val p = server.addPlayer()
        p.teleport(Location(world, 0.5, 64.0, 0.5))
        triggers.area(owner, Area.box(world, 10, 60, 10, 40, 70, 40), AreaHandler.onEnter { _, _, _ -> })
        triggers.watchSteps(owner, p) { _, _, _, _ -> }
        triggers.every(owner, 20) {}
        p.simulatePlayerMove(Location(world, 1.5, 64.0, 0.5))
        val s = triggers.stats()
        assertEquals(mapOf("area" to 1, "step-watch" to 1, "timer" to 1), s.byKind)
        assertEquals(mapOf("Owner" to mapOf("area" to 1, "step-watch" to 1, "timer" to 1)), s.byOwner)
        assertEquals(9, s.areaChunks, "blocks 10..40 span chunks 0..2 on both axes")
        assertEquals(1, s.trackedPlayers)
        assertEquals(1L, s.counter("events.move"))
        assertTrue(s.counter("steps") >= 1)
        assertEquals(1L, s.counter("handler.calls"), "the step watch, once")
        assertEquals(3, s.subscriptions.size)
        assertThrows(IllegalStateException::class.java) {
            java.util.concurrent.Executors.newSingleThreadExecutor().submit<Unit> { triggers.stats() }.let {
                try {
                    it.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause!!
                }
            }
        }
    }
}
