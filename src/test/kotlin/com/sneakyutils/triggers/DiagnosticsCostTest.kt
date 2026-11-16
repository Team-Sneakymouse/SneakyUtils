package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * What handler attribution costs on the service's hot path ([TriggerService.moved], the PlayerMoveEvent path after the
 * block compare), with labelled area handlers firing: without SneakyDiagnostics, with it installed but no capture, and
 * during a capture (the fake probe records nothing, so this is SneakyUtils' own cost). No capture must allocate nothing
 * and make no probe call per move or per handler. Numbers are printed; time budgets are generous.
 */
class DiagnosticsCostTest {
    private lateinit var server: ServerMock
    private lateinit var service: TriggerService
    private lateinit var world: WorldMock
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private var handlerCalls = 0L

    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    /** 100 players walking along x; every 8 blocks a labelled 4-block area (enter and exit fire), 40 labels. */
    private fun setUp(probe: FakeProbe?): List<UUID> {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        if (probe != null) {
            val diag = MockBukkit.createMockPlugin("SneakyDiagnostics")
            server.servicesManager.register(Diagnostics::class.java, FakeDiagnostics(probe), diag, ServicePriority.Normal)
        }
        MockBukkit.load(SneakyUtils::class.java)
        val owner: Plugin = MockBukkit.createMockPlugin("Owner")
        service = SneakyUtils.getTriggers() as TriggerService
        val handler = AreaHandler.of({ _, _, _ -> handlerCalls++ }, { _, _, _ -> handlerCalls++ })
        for (i in 0 until 512) {
            val x = i * 8
            service.labelled("owner:area-${i % 40}").area(owner, Area.box(world, x, 0, -10, x + 3, 255, 1000), handler)
        }
        return (0 until 100).map { i ->
            val p = server.addPlayer("p$i")
            p.teleport(Location(world, 0.5 + 4, 64.0, i * 2.5 + 0.5))
            p.uniqueId
        }
    }

    /** [direct]: the engine call the listener made before stages existed (core.position), for comparison. */
    private fun run(ids: List<UUID>, steps: Int, offset: Int, direct: Boolean = false) {
        val w = world.uid
        val svc = service
        val core = svc.core
        for (i in 0 until steps) {
            val x = ((i / 100 + offset) % 4000) + 0.5
            if (direct) core.position(ids[i % 100], w, x, 64.0, (i % 100) * 2.5 + 0.5, false)
            else svc.moved(ids[i % 100], w, x, 64.0, (i % 100) * 2.5 + 0.5)
        }
    }

    private class Result(val nsPerMove: Double, val bytes: Long, val handlerCalls: Long)

    private fun measure(ids: List<UUID>, direct: Boolean = false): Result {
        repeat(5) { run(ids, 200_000, it * 2_000, direct) }
        val calls0 = handlerCalls
        val before = allocated()
        val t0 = System.nanoTime()
        run(ids, 1_000_000, 777, direct)
        val nanos = System.nanoTime() - t0
        return Result(nanos / 1_000_000.0, allocated() - before, handlerCalls - calls0)
    }

    @Test
    fun noCaptureAllocatesNothingAndMakesNoProbeCallPerMoveOrHandler() {
        val probe = FakeProbe()
        val ids = setUp(probe)
        val onCalls = probe.onCalls
        val calls = service.handlerCalls
        val r = measure(ids)
        println("DiagnosticsCostTest idle probe: ${"%.1f".format(r.nsPerMove)} ns/move, ${r.handlerCalls} handler calls, ${r.bytes} bytes")
        assertTrue(r.handlerCalls > 200_000, "handlers fired: ${r.handlerCalls}")
        assertTrue(r.bytes < 16 * 1024, "1,000,000 moves with ${r.handlerCalls} labelled handler calls allocated ${r.bytes} bytes")
        assertEquals(0, probe.startCalls, "no section was opened")
        // One on() per drain (a move with handler calls), never per handler call or per no-op move.
        assertTrue(probe.onCalls - onCalls <= service.handlerCalls - calls, "on() calls ${probe.onCalls - onCalls}")
        assertTrue(r.nsPerMove < 5_000, "${r.nsPerMove} ns a move")
    }

    @Test
    fun captureCostAndAllocationStayBounded() {
        val probe = FakeProbe().also { it.record = false }
        val ids = setUp(probe)
        probe.capturing = true
        service.refreshTiming()
        val r = measure(ids)
        println("DiagnosticsCostTest capturing: ${"%.1f".format(r.nsPerMove)} ns/move, ${r.handlerCalls} handler calls, ${r.bytes} bytes")
        // Names are resolved once per subscription per capture (and cached), so a capture allocates nothing per call.
        assertTrue(r.bytes < 64 * 1024, "capture allocated ${r.bytes} bytes")
        assertTrue(r.nsPerMove < 20_000, "${r.nsPerMove} ns a move")
    }

    @Test
    fun comparedWithoutSneakyDiagnostics() {
        // Same workload, three ways, in one JVM. Printed for the record; the assertion is loose (CI machines vary).
        val absent = setUp(null).let { measure(it) }
        MockBukkit.unmock()
        val idle = setUp(FakeProbe()).let { measure(it) }
        MockBukkit.unmock()
        val ids = setUp(null)
        val absentAgain = measure(ids)
        val direct = measure(ids, direct = true)
        println(
            "DiagnosticsCostTest per move with handlers: absent ${"%.1f".format(absent.nsPerMove)} / " +
                "${"%.1f".format(absentAgain.nsPerMove)} ns, installed idle ${"%.1f".format(idle.nsPerMove)} ns, " +
                "engine called directly (the path before stages) ${"%.1f".format(direct.nsPerMove)} ns " +
                "(bytes ${absent.bytes} / ${idle.bytes} / ${direct.bytes})",
        )
        assertTrue(absent.bytes < 16 * 1024 && idle.bytes < 16 * 1024)
        val base = minOf(absent.nsPerMove, absentAgain.nsPerMove)
        assertTrue(idle.nsPerMove < base * 2 + 200, "idle ${idle.nsPerMove} ns vs absent $base ns")
    }
}
