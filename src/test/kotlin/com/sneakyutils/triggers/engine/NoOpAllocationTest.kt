package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.OccupancyOptions
import com.sneakyutils.triggers.WatchOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * The performance contract's no-op rule, measured: a million block steps through the engine core (no server, no
 * Bukkit objects) allocate nothing, and stay well inside a generous time budget.
 *
 * The world has areas (some pass-through) far from the players, and 100 players, so the index and the per-player
 * map are realistic. Steps cross chunk borders every 16 blocks, so the chunk-change lookup is exercised.
 */
class NoOpAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    private class Setup(mounted: Boolean, footWatch: Boolean) {
        val world: UUID = UUID.randomUUID()
        val sink = RecordingSink()
        val index = AreaIndex()
        val core = MovementCore(index, sink)
        val states = ArrayList<PlayerState>()

        init {
            // ~1,000 areas elsewhere in the world, some pass-through, none near z 0..63.
            for (i in 0 until 1000) {
                val x = (i % 40) * 64
                val z = 10_000 + (i / 40) * 64
                val opts = if (i % 10 == 0) AreaOptions.builder().passThrough(true).build() else AreaOptions.DEFAULT
                core.addArea(AreaReg(Area.box(world, x, 0, z, x + 8, 255, z + 8), opts))
            }
            for (i in 0 until 100) {
                val s = core.join(UUID.randomUUID(), null, world, 0.5, 64.0, i * 0.5 + 0.5, mounted, false)
                if (footWatch) core.addWatch(StepWatch(s.uuid, null, WatchOptions.DEFAULT))
                states += s
            }
        }

        /** [n] steps round-robin over the players; each one moves to the next block along x (a sawtooth). */
        fun run(n: Int, offset: Int) {
            val w = world
            val ss = states
            for (i in 0 until n) {
                val s = ss[i % 100]
                val x = ((i / 100 + offset) % 4096) + 0.5
                core.position(s, w, x, 64.0, s.pz, false)
            }
        }
    }

    private fun measure(setup: Setup) {
        // Warm up so the loop is compiled before measuring (JIT compilation runs on other threads).
        repeat(5) { setup.run(200_000, it * 2000) }
        val stepsBefore = setup.core.steps
        val before = allocated()
        val t0 = System.nanoTime()
        setup.run(1_000_000, 777)
        val nanos = System.nanoTime() - t0
        val bytes = allocated() - before
        assertEquals(1_000_000L, setup.core.steps - stepsBefore, "every report was a block change")
        // Zero in practice; the slack covers the measurement calls themselves. One allocation per step would be
        // at least 16 MB here.
        assertTrue(bytes < 16 * 1024, "1,000,000 no-op steps allocated $bytes bytes")
        // About 20-50 ns a step on a desktop; 2 s is a generous, non-flaky ceiling (2 us a step).
        assertTrue(nanos < 2_000_000_000L, "1,000,000 no-op steps took ${nanos / 1_000_000} ms")
        assertTrue(setup.sink.log.isEmpty())
    }

    @Test
    fun noOpStepsDoNotAllocate() {
        val setup = Setup(mounted = false, footWatch = false)
        measure(setup)
        assertEquals(setup.core.steps, setup.core.noopSteps)
    }

    @Test
    fun stepsInsideOccupanciesDoNotAllocate() {
        // 100 players walking inside two occupancies (a box and a ball around them): every step re-checks both
        // memberships (box test, ball predicate, spectator rule) and changes nothing, like an area's steps inside.
        val world = UUID.randomUUID()
        val sink = RecordingSink()
        val core = MovementCore(AreaIndex(), sink)
        val o = AreaOptions.builder().riders(com.sneakyutils.triggers.RiderPolicy.ALLOW).fireOnJoin(true).fireOnRegister(true).build()
        core.addArea(AreaReg(Area.box(world, 0, 0, 0, 1023, 255, 1023), o, OccupancyOptions.DEFAULT))
        core.addArea(AreaReg(Area.sphere(world, 512.0, 64.0, 512.0, 500.0), o, OccupancyOptions.DEFAULT))
        val states = (0 until 100).map { core.join(UUID.randomUUID(), null, world, 300.5, 64.0, 300.5 + it, false, false) }
        sink.log.clear()
        fun run(n: Int, offset: Int) {
            for (i in 0 until n) {
                val s = states[i % 100]
                core.position(s, world, 300.5 + ((i / 100 + offset) % 400), 64.0, s.pz, false)
            }
        }
        repeat(5) { run(200_000, it * 2000) }
        val before = allocated()
        val t0 = System.nanoTime()
        run(1_000_000, 777)
        val nanos = System.nanoTime() - t0
        val bytes = allocated() - before
        assertTrue(bytes < 16 * 1024, "1,000,000 steps inside occupancies allocated $bytes bytes")
        assertTrue(nanos < 2_000_000_000L, "1,000,000 steps inside occupancies took ${nanos / 1_000_000} ms")
        assertTrue(sink.log.isEmpty())
        assertTrue(states.all { it.memberCount == 2 })
    }

    @Test
    fun ridersWithOnFootWatchesDoNotAllocateEither() {
        // The acceptance case for drivers: on-foot watches are active but paused, no rider areas nearby.
        measure(Setup(mounted = true, footWatch = true))
    }
}
