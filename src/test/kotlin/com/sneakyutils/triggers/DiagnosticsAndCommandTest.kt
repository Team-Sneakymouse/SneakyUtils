package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock

class DiagnosticsAndCommandTest {
    private lateinit var server: ServerMock
    private lateinit var su: SneakyUtils
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers

    private fun start(withDiagnostics: FakeDiagnostics?) {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        if (withDiagnostics != null) {
            val diag = MockBukkit.createMockPlugin("SneakyDiagnostics")
            server.servicesManager.register(Diagnostics::class.java, withDiagnostics, diag, ServicePriority.Normal)
        }
        su = MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // ---------------------------------------------------------------- SneakyDiagnostics

    @Test
    fun withoutSneakyDiagnosticsNothingIsTimedOrScheduled() {
        start(null)
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner === su })
        var calls = 0
        triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> calls++ })
        val p = server.addPlayer()
        p.teleport(Location(world, 2.5, 64.0, 2.5))
        assertEquals(1, calls)
        assertFalse(triggers.stats().timing)
        assertEquals(0L, triggers.stats().subscriptions.single().nanos)
    }

    @Test
    fun handlersAndTicksAreTimedOnlyDuringACapture() {
        val probe = FakeProbe()
        val diagnostics = FakeDiagnostics(probe)
        start(diagnostics)
        assertEquals("sneakyutils-ticks-2", diagnostics.instrumentation)
        triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> })
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), probe.sections, "no capture: nothing recorded")

        probe.capturing = true
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        // The first move's drain notices the capture (timing on); from the second move on the move itself is a stage.
        assertEquals(
            listOf("handler.owner.area-exit", "drain", "handler.owner.area-enter", "drain", "move"),
            probe.sections,
            "the exit (default no-op) and the enter, inside their drain and move",
        )
        assertEquals(0, probe.nestingErrors)
        assertTrue(triggers.stats().subscriptions.single { it.kind == "area" }.nanos > 0)

        probe.sections.clear()
        triggers.every(owner, 1) {}
        server.scheduler.performOneTick()
        assertEquals(listOf("handler.owner.timer", "tick.timers", "tick"), probe.sections, "strictly nested, inner first")
        assertEquals(0, probe.nestingErrors)
    }

    @Test
    fun occupancyCallbacksAndMovesAreAttributedWithTheirLabel() {
        val probe = FakeProbe().also { it.capturing = true; it.due = true }
        start(FakeDiagnostics(probe))
        val occ = triggers.labelled("owner:station").occupancy(owner, Area.box(world, 0, 60, 0, 5, 70, 5), object : OccupancyHandler {})
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        occ.moveTo(Area.box(world, 100, 60, 0, 105, 70, 5))
        for (name in listOf("first-in", "enter", "exit", "last-out")) {
            assertTrue("handler.owner.occupancy-$name.owner:station" in probe.sections, probe.sections.toString())
        }
        assertTrue("area-index.move" in probe.sections, probe.sections.toString())
        assertEquals(0, probe.nestingErrors)
        server.scheduler.performTicks(20)
        assertEquals(1L, probe.levels["subscriptions.by-kind.occupancy"])
        assertEquals(1L, probe.levels["subscriptions.by-owner.owner.occupancy"])
    }

    @Test
    fun sneakyDiagnosticsInstalledAfterStartupGetsTheSamplerAndAttribution() {
        start(null)
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner === su })
        triggers.labelled("owner:gate").area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> })
        val probe = FakeProbe().also { it.capturing = true; it.due = true }
        val diag = MockBukkit.createMockPlugin("SneakyDiagnostics")
        server.servicesManager.register(Diagnostics::class.java, FakeDiagnostics(probe), diag, ServicePriority.Normal)
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner === su }, "the sampler starts with the service")
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertTrue("handler.owner.area-enter.owner:gate" in probe.sections, probe.sections.toString())
        server.scheduler.performTicks(20)
        assertEquals(1L, probe.levels["subscriptions.by-owner.owner.area"])

        // The service goes away during a capture: timing is off at once, not at the next event.
        server.servicesManager.unregisterAll(diag)
        assertFalse((triggers as TriggerService).timing)
    }

    @Test
    fun noOpMovesNeverAskTheProbe() {
        val probe = FakeProbe()
        start(FakeDiagnostics(probe))
        probe.capturing = true
        val p = server.addPlayer()
        p.teleport(Location(world, 0.5, 64.0, 0.5))
        val before = probe.onCalls
        for (i in 1..100) p.simulatePlayerMove(Location(world, i + 0.5, 64.0, 0.5))
        assertEquals(before, probe.onCalls, "the no-op path makes no probe call")
    }

    @Test
    fun samplerReportsCounterDeltasAndLevelsDuringACapture() {
        val probe = FakeProbe()
        start(FakeDiagnostics(probe))
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner === su }, "the once-a-second sampler")
        val p = server.addPlayer()
        p.teleport(Location(world, 0.5, 64.0, 0.5))
        probe.capturing = true
        probe.due = true
        server.scheduler.performTicks(20) // first sample: baseline only
        for (i in 1..30) p.simulatePlayerMove(Location(world, i + 0.5, 64.0, 0.5))
        server.scheduler.performTicks(20)
        assertEquals(30L, probe.counts["steps"])
        assertEquals(30L, probe.counts["steps.noop"])
        assertEquals(1L, probe.levels["players.tracked"])
        assertEquals(0L, probe.levels["players.riding"])
    }

    @Test
    fun profilingTogglesScheduleNothingAndDisableStopsTheSampler() {
        val probe = FakeProbe()
        start(FakeDiagnostics(probe))
        val admin = server.addPlayer()
        admin.addAttachment(su, "sneakyutils.command.triggers", true)
        val service = triggers as TriggerService
        repeat(100) {
            server.dispatchCommand(admin, "triggers profile on")
            assertTrue(service.timing)
            server.dispatchCommand(admin, "triggers profile off")
            assertFalse(service.timing)
        }
        messages(admin)
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner === su }, "still only the sampler")
        probe.capturing = true
        server.scheduler.performTicks(40)
        server.pluginManager.disablePlugin(su)
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner === su }, "the sampler ends with SneakyUtils")
        val calls = probe.onCalls
        server.scheduler.performTicks(100)
        assertEquals(calls, probe.onCalls, "nothing asks the probe after disable")
        assertFalse(service.running)
    }

    // ---------------------------------------------------------------- /triggers

    private fun messages(p: PlayerMock): List<String> {
        val out = ArrayList<String>()
        while (true) out += p.nextMessage() ?: break
        return out
    }

    @Test
    fun costlyLayoutsAreFlaggedInStatsTheCommandAndOnceInTheLog() {
        start(null)
        val logged = ArrayList<String>()
        su.logger.addHandler(object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                logged += record.message
            }

            override fun flush() {}
            override fun close() {}
        })
        val admin = server.addPlayer()
        admin.addAttachment(su, "sneakyutils.command.triggers", true)
        assertEquals(emptyList<String>(), triggers.stats().warnings)
        repeat(300) { triggers.area(owner, Area.box(world, 0, 60, 0, 15, 70, 15), object : AreaHandler {}) }
        val q = server.addPlayer()
        repeat(20) { triggers.watchSteps(owner, q) { _, _, _, _ -> } }
        repeat(70) { triggers.onBlockClick(owner, BlockKey.of(world, 1, 2, 3), BlockClickHandler { _, _, _, _ -> false }) }

        val warnings = triggers.stats().warnings
        assertEquals(3, warnings.size, warnings.toString())
        assertTrue(warnings[0].startsWith("chunk 0,0 in world lists 300 areas"), warnings[0])
        assertTrue(warnings[1].startsWith("a player has 20 watches"), warnings[1])
        assertTrue(warnings[2].startsWith("click block 1,2,3 in world has 70 subscriptions"), warnings[2])
        assertEquals(1, logged.count { it.contains("trigger areas") }, "logged once, when the chunk first got dense")

        server.dispatchCommand(admin, "triggers")
        val lines = messages(admin)
        assertEquals(3, lines.count { it.startsWith("Warning: ") }, lines.toString())
        assertTrue(lines.last().startsWith("Counters:"))
    }

    @Test
    fun sneakyUtilsStaysOffWhenEnabledAgain() {
        // Found live (Paper 26.2): Paper closes a plugin's jar when it is disabled. Enabled again (plugman), SneakyUtils
        // ran from the closed jar and its class loader went back in the lookup chain, so the next /ms reload failed for
        // every plugin. It now stays off; /triggers (registered once at load) says the service isn't running.
        start(null)
        server.dispatchCommand(server.consoleSender, "triggers") // commands registered, as at server start
        while (server.consoleSender.nextMessage() != null) Unit
        server.pluginManager.disablePlugin(su)
        assertTrue(SneakyUtils.triggersOrNull() == null)
        server.pluginManager.enablePlugin(su)
        assertFalse(su.isEnabled)
        assertNull(SneakyUtils.triggersOrNull())
        server.dispatchCommand(server.consoleSender, "triggers")
        assertEquals("SneakyUtils triggers are not running.", server.consoleSender.nextMessage())
    }

    @Test
    fun commandNeedsThePermission() {
        start(null)
        val p = server.addPlayer()
        server.dispatchCommand(p, "triggers")
        assertTrue(messages(p).none { it.startsWith("Triggers:") })
    }

    @Test
    fun statsTopAndProfile() {
        start(null)
        val p = server.addPlayer()
        p.addAttachment(su, "sneakyutils.command.triggers", true)
        triggers.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> })
        triggers.everyForPlayers(owner, 20, { }, PlayerScope.ALL_ONLINE)

        server.dispatchCommand(p, "triggers")
        val stats = messages(p)
        assertTrue(stats[0].startsWith("Triggers: 2 subscriptions"), stats[0])
        assertTrue(stats.any { it.startsWith("All-online player timers: 1") }, stats.toString())
        assertTrue(stats.any { it.contains("Owner: area 1, player-timer-all-online 1") }, stats.toString())
        assertTrue(stats.last().startsWith("Counters: events.move="), stats.last())

        server.dispatchCommand(p, "triggers profile on")
        assertEquals(listOf("Trigger profiling on (handler times reset)."), messages(p))
        assertTrue((triggers as TriggerService).timing)
        p.teleport(Location(world, 2.5, 64.0, 2.5))
        server.dispatchCommand(p, "triggers top")
        val top = messages(p)
        assertTrue(top.contains("Top by calls:"), top.toString())
        assertTrue(top.contains("Top by time:"), top.toString())
        assertTrue(top.any { it.contains("Owner:area#") }, top.toString())
        assertTrue(top.any { it.contains("us/call  timed calls 1  slow 0") }, top.toString())

        server.dispatchCommand(p, "triggers profile off")
        assertEquals(listOf("Trigger profiling off."), messages(p))
        assertFalse((triggers as TriggerService).timing)
    }
}
