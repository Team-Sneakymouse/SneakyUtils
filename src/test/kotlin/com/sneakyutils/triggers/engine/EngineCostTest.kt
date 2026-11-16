package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the movement engine costs per server tick in the target load, and in the worst layouts an admin could make.
 * Engine core only: no Bukkit event dispatch, no `getLocation`, no handler bodies (a counting sink). The numbers are
 * printed; the asserted budgets are generous (several times the measured cost on a desktop) so CI doesn't flake.
 *
 * `-Dtriggers.soak=N` runs N times as many ticks.
 */
class EngineCostTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
    private val scale: Int get() = System.getProperty("triggers.soak")?.toLongOrNull()?.coerceIn(1, 1000)?.toInt() ?: 1

    /** Counts deliveries; block types are a cheap fixed pattern. */
    private class CountingSink : EngineSink {
        var calls = 0L
        override fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause) { calls++ }
        override fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause) { calls++ }
        override fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause) { calls++ }
        override fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean) { calls++ }
        override fun blockWatch(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch) { calls++ }
        override fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material = TYPES[(x * 31 + z * 17 + y) and 7]
        override fun predicateFailed(reg: AreaReg, error: Throwable) {}
        override fun watchEnded(watch: Watch) {}
        override fun eventsDropped(count: Int, lastSubject: Any?) {}

        companion object {
            private val TYPES = arrayOf(Material.AIR, Material.STONE, Material.GRASS_BLOCK, Material.SAND,
                Material.WATER, Material.STONE, Material.DIRT, Material.AIR)
        }
    }

    /** 100 players moving each tick: [riders] in vehicles at about 2 blocks a tick, the rest walking. */
    private class Load(val core: MovementCore, val world: UUID, regionBlocks: Int, riders: Int, seed: Long) {
        private val r = SplittableRandom(seed)
        val states = ArrayList<PlayerState>()
        private val x = DoubleArray(100)
        private val z = DoubleArray(100)
        private val heading = DoubleArray(100)
        private val speed = DoubleArray(100)
        private val limit = regionBlocks.toDouble()

        init {
            for (i in 0 until 100) {
                x[i] = r.nextDouble() * limit
                z[i] = r.nextDouble() * limit
                heading[i] = r.nextDouble() * Math.PI * 2
                val riding = i < riders
                speed[i] = if (riding) 1.6 + r.nextDouble() * 0.8 else 0.2 + r.nextDouble() * 0.1
                states += core.join(UUID(7, i.toLong()), null, world, x[i], 64.0, z[i], riding, false)
            }
        }

        fun tick(t: Int) {
            for (i in 0 until 100) {
                if ((t + i) % 120 == 0) heading[i] += r.nextDouble() - 0.5
                x[i] += cos(heading[i]) * speed[i]
                z[i] += sin(heading[i]) * speed[i]
                if (x[i] < 0 || x[i] > limit) heading[i] = Math.PI - heading[i]
                if (z[i] < 0 || z[i] > limit) heading[i] = -heading[i]
                x[i] = x[i].coerceIn(0.0, limit)
                z[i] = z[i].coerceIn(0.0, limit)
                core.position(states[i], world, x[i], 64.0, z[i], false)
            }
        }
    }

    private class Result(val usPerTick: Double, val bytesPerTick: Double, val steps: Long, val calls: Long)

    private fun run(areas: Int, regionBlocks: Int, ticks: Int, label: String): Result {
        val sink = CountingSink()
        val core = MovementCore(AreaIndex(), sink)
        val world = UUID.randomUUID()
        val r = SplittableRandom(areas * 31L + regionBlocks)
        repeat(areas) { i ->
            val sx = 8 + r.nextInt(40)
            val sz = 8 + r.nextInt(40)
            val ax = r.nextInt(regionBlocks - sx)
            val az = r.nextInt(regionBlocks - sz)
            val opts = AreaOptions.builder()
                .passThrough(i % 10 == 0)
                .riders(if (i % 10 < 6) RiderPolicy.SKIP else if (i % 10 < 9) RiderPolicy.ALLOW else RiderPolicy.ONLY)
                .stepEvents(i % 3 == 0).build()
            core.addArea(AreaReg(Area.box(world, ax, 50, az, ax + sx, 90, az + sz), opts))
        }
        val load = Load(core, world, regionBlocks, riders = 70, seed = 99)
        // 300 watches: per player, a paused on-foot step watch, a step watch that also counts riding, and a
        // block-under watch that also counts riding (the pessimistic mix: two of three fire on every block step).
        val riding = WatchOptions.builder().onFootOnly(false).build()
        for (s in load.states) {
            core.addWatch(StepWatch(s.uuid, null, WatchOptions.DEFAULT))
            core.addWatch(StepWatch(s.uuid, null, riding))
            core.addWatch(BlockWatch(s.uuid, BlockOffset.BELOW, setOf(Material.SAND, Material.WATER), null, false, riding))
        }
        for (t in 0 until 2_000) load.tick(t) // warm up (JIT)
        val steps0 = core.steps
        val calls0 = sink.calls
        val samples = DoubleArray(5)
        var bytes = 0L
        for (rep in samples.indices) {
            val a0 = allocated()
            val t0 = System.nanoTime()
            for (t in 0 until ticks) load.tick(2_000 + rep * ticks + t)
            samples[rep] = (System.nanoTime() - t0) / 1_000.0 / ticks
            bytes += allocated() - a0
        }
        samples.sort()
        val res = Result(samples[2], bytes.toDouble() / (ticks * samples.size), core.steps - steps0, sink.calls - calls0)
        println("EngineCostTest $label: ${"%.1f".format(res.usPerTick)} us/tick (median of 5 x $ticks ticks), " +
            "${"%.0f".format(res.bytesPerTick)} B/tick, ${res.steps / (ticks * 5)} steps/tick, " +
            "${"%.1f".format(res.calls.toDouble() / (ticks * 5))} handler calls/tick")
        return res
    }

    @Test
    fun hundredPlayersInFastVehiclesWithAThousandAreasAndThreeHundredWatches() {
        val spread = run(areas = 1_000, regionBlocks = 10_000, ticks = 2_000 * scale, label = "1,000 areas over 10k x 10k, 100 players (70 in vehicles), 300 watches")
        val town = run(areas = 1_000, regionBlocks = 1_000, ticks = 2_000 * scale, label = "1,000 areas over 1k x 1k (a dense town), same players and watches")
        // Contract: the whole service under 1 ms a tick. The engine core takes a small share of it; 500 us is a
        // generous ceiling for slow CI machines (the desktop measures well under 100).
        assertTrue(spread.usPerTick < 500, "spread: ${spread.usPerTick} us/tick")
        assertTrue(town.usPerTick < 500, "town: ${town.usPerTick} us/tick")
    }

    /**
     * The worst layouts: many areas overlapping one chunk with every player inside it, one area as big as allowed,
     * and 10,000 one-block areas. Prints the cost per step; asserts it grows no faster than the areas a step must
     * check (bounded work), and that registering stays fast.
     */
    @Test
    fun denseChunksHugeAreasAndManyTinyAreas() {
        for (n in intArrayOf(10, 100, 1_000, 10_000)) {
            val sink = CountingSink()
            val core = MovementCore(AreaIndex(), sink)
            val world = UUID.randomUUID()
            val tReg = System.nanoTime()
            // n boxes overlapping chunk (0, 0); a quarter of them contain the whole chunk, so players are members.
            for (i in 0 until n) {
                val box = if (i % 4 == 0) Area.box(world, 0, 0, 0, 15, 255, 15)
                else Area.box(world, i % 16, 60, (i / 16) % 16, i % 16, 70, (i / 16) % 16)
                core.addArea(AreaReg(box, if (i % 8 == 0) AreaOptions.builder().stepEvents(true).build() else AreaOptions.DEFAULT))
            }
            val regMs = (System.nanoTime() - tReg) / 1_000_000.0
            val players = (0 until 100).map { core.join(UUID(9, it.toLong()), null, world, 0.5 + it % 16, 64.0, 0.5 + it / 16 % 16, false, false) }
            val stepsEach = maxOf(200, 2_000_000 / n) * scale
            var k = 0
            fun walk(steps: Int) {
                repeat(steps) {
                    val s = players[k % 100]
                    val x = ((k / 100) % 15) + 0.5
                    core.position(s, world, x, 64.0, s.pz, false)
                    k++
                }
            }
            walk(stepsEach / 4)
            val t0 = System.nanoTime()
            walk(stepsEach)
            val usPerStep = (System.nanoTime() - t0) / 1_000.0 / stepsEach
            println("EngineCostTest dense chunk, $n areas: ${"%.2f".format(usPerStep)} us/step, registering took " +
                "${"%.1f".format(regMs)} ms, ${players[0].memberCount} memberships per player")
            // Work per step is linear in the areas listed in the chunk: about 0.1 us per area on a desktop.
            assertTrue(usPerStep < 5.0 + n * 0.5, "$n areas: $usPerStep us/step")
            assertTrue(regMs < 2_000 + n * 0.5, "$n areas: registering took $regMs ms")
        }

        val sink = CountingSink()
        val core = MovementCore(AreaIndex(), sink)
        val world = UUID.randomUUID()
        var t0 = System.nanoTime()
        val huge = AreaReg(Area.box(world, 0, -64, 0, 64 * 16 - 1, 319, 64 * 16 - 1), AreaOptions.DEFAULT)
        core.addArea(huge)
        val hugeMs = (System.nanoTime() - t0) / 1_000_000.0
        t0 = System.nanoTime()
        val tiny = (0 until 10_000).map { i ->
            AreaReg(Area.box(world, (i % 100) * 10, 64, (i / 100) * 10, (i % 100) * 10, 64, (i / 100) * 10), AreaOptions.DEFAULT)
                .also { core.addArea(it) }
        }
        val tinyMs = (System.nanoTime() - t0) / 1_000_000.0
        t0 = System.nanoTime()
        core.removeArea(huge, false)
        for (reg in tiny) core.removeArea(reg, false)
        val removeMs = (System.nanoTime() - t0) / 1_000_000.0
        println("EngineCostTest one 64x64-chunk area: registered in ${"%.1f".format(hugeMs)} ms (4,096 chunk entries); " +
            "10,000 one-block areas: ${"%.1f".format(tinyMs)} ms; removing all: ${"%.1f".format(removeMs)} ms")
        assertTrue(hugeMs < 500 && tinyMs < 2_000 && removeMs < 2_000)
    }
}
