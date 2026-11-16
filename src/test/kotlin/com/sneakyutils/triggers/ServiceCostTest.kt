package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Boat
import org.bukkit.entity.EntityType
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.SplittableRandom
import kotlin.math.cos
import kotlin.math.sin

/**
 * Costs of the service around the engine: timer dispatch, click lookups and a whole ticker pass with riders. Runs on
 * MockBukkit, whose players and blocks are simpler than Paper's, so these are the service's own costs plus MockBukkit's
 * accessors, not a server measurement. Numbers are printed; budgets are generous.
 */
class ServiceCostTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var service: TriggerService
    private val players = ArrayList<PlayerMock>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        service = SneakyUtils.getTriggers() as TriggerService
        repeat(100) { i ->
            val p = server.addPlayer("p$i")
            p.teleport(Location(world, i * 3.5, 64.0, 0.5))
            players += p
        }
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun fiftyTimersWithAHundredPlayers() {
        var calls = 0L
        // 40 global timers over common intervals, 10 per-player timers with all 100 players.
        val globalIntervals = intArrayOf(1, 2, 5, 10, 20, 20, 40, 100, 200, 1_200, 6_000, 72_000)
        repeat(40) { i -> service.every(owner, globalIntervals[i % globalIntervals.size]) { calls++ } }
        val playerIntervals = intArrayOf(1, 2, 4, 5, 10, 20, 20, 40, 100, 200)
        for (interval in playerIntervals) {
            val t = service.everyForPlayers(owner, interval, { calls++ })
            for (p in players) t.add(p)
        }
        assertEquals(50, service.stats().byKind.values.sum())
        val ticks = 40_000
        repeat(5_000) { service.ticks++; service.runTimers() }
        calls = 0
        val t0 = System.nanoTime()
        repeat(ticks) {
            service.ticks++
            service.runTimers()
        }
        val usPerTick = (System.nanoTime() - t0) / 1_000.0 / ticks
        val perTick = calls.toDouble() / ticks
        println("ServiceCostTest timers: 50 timers, 100 players: ${"%.2f".format(usPerTick)} us/tick, " +
            "${"%.1f".format(perTick)} handler calls/tick")
        // N players on interval I cost N / I calls a tick: 100 * (1 + 1/2 + 1/4 + 1/5 + 1/10 + 2/20 + 1/40 + ...).
        assertTrue(perTick in 200.0..240.0, "calls per tick $perTick")
        assertTrue(usPerTick < 300, "timers: $usPerTick us/tick")
    }

    @Test
    fun clickLookupsWithTenThousandRegisteredBlocks() {
        val r = SplittableRandom(5)
        var hits = 0L
        val registered = HashSet<Long>()
        while (registered.size < 10_000) {
            val x = r.nextInt(2_000) - 1_000
            val z = r.nextInt(2_000) - 1_000
            if (!registered.add(x.toLong() * 100_000 + z)) continue
            service.onBlockClick(owner, BlockKey.of(world, x, 64, z), BlockClickHandler { _, _, _, _ -> hits++; false })
        }
        for (m in listOf(Material.LEVER, Material.STONE_BUTTON, Material.OAK_DOOR)) {
            repeat(10) { service.onBlockType(owner, m, BlockClickHandler { _, _, _, _ -> hits++; false }) }
        }
        val p = players[0]
        val misses = (0 until 1_000).map { i ->
            val b = world.getBlockAt(5_000 + i, 64, 5_000)
            PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, b, BlockFace.UP, EquipmentSlot.HAND) to b
        }
        val hitEvents = registered.take(1_000).map { key ->
            val b = world.getBlockAt((key / 100_000).toInt(), 64, (key % 100_000).toInt().let { if (it > 50_000) it - 100_000 else it })
            PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, b, BlockFace.UP, EquipmentSlot.HAND) to b
        }
        fun measure(events: List<Pair<PlayerInteractEvent, org.bukkit.block.Block>>, rounds: Int): Double {
            repeat(rounds / 5) { for ((e, b) in events) service.clicks.interact(e, b, ClickSide.RIGHT) }
            val t0 = System.nanoTime()
            repeat(rounds) { for ((e, b) in events) service.clicks.interact(e, b, ClickSide.RIGHT) }
            return (System.nanoTime() - t0).toDouble() / (rounds * events.size)
        }
        val missNs = measure(misses, 200)
        hits = 0
        val hitNs = measure(hitEvents, 50)
        assertTrue(hits > 0)

        // Entities: 1,000 subscriptions on one type (tag matchers) and 20 "any type" ones.
        repeat(1_000) { i -> service.onEntityClick(owner, EntityMatch.scoreboardTag("t$i").withType(EntityType.VILLAGER), EntityClickHandler { _, _, _, _ -> false }) }
        repeat(20) { i -> service.onEntityClick(owner, EntityMatch.scoreboardTag("any$i"), EntityClickHandler { _, _, _, _ -> false }) }
        val cow = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.COW)
        val villager = world.spawnEntity(Location(world, 1.0, 64.0, 0.0), EntityType.VILLAGER)
        val onCow = PlayerInteractEntityEvent(p, cow, EquipmentSlot.HAND)
        val onVillager = PlayerInteractEntityEvent(p, villager, EquipmentSlot.HAND)
        fun entityNs(e: PlayerInteractEntityEvent, rounds: Int): Double {
            repeat(rounds / 5) { service.clicks.entity(e, p, e.rightClicked, ClickSide.RIGHT, EquipmentSlot.HAND) }
            val t0 = System.nanoTime()
            repeat(rounds) { service.clicks.entity(e, p, e.rightClicked, ClickSide.RIGHT, EquipmentSlot.HAND) }
            return (System.nanoTime() - t0).toDouble() / rounds
        }
        val cowNs = entityNs(onCow, 20_000)
        val villagerNs = entityNs(onVillager, 2_000)
        println("ServiceCostTest clicks, 10,000 registered blocks + 30 type subscriptions: miss ${"%.0f".format(missNs)} ns, " +
            "hit ${"%.0f".format(hitNs)} ns; entity click with 20 any-type matchers: ${"%.0f".format(cowNs)} ns, " +
            "on the type with 1,000 tag matchers: ${"%.0f".format(villagerNs / 1000)} us")
        assertTrue(missNs < 5_000 && hitNs < 50_000, "block clicks: miss $missNs ns, hit $hitNs ns")
        assertTrue(cowNs < 50_000, "entity click: $cowNs ns")
    }

    @Test
    fun aWholeTickerPassWithSeventyRiders() {
        // 1,000 areas over 10k x 10k, 70 players riding boats at 2 blocks a tick, polled by the real ticker.
        val r = SplittableRandom(3)
        repeat(1_000) { i ->
            val ax = r.nextInt(9_950)
            val az = r.nextInt(9_950)
            service.area(owner, Area.box(world, ax, 50, az, ax + 8 + r.nextInt(40), 90, az + 8 + r.nextInt(40)), object : AreaHandler {},
                AreaOptions.builder().riders(if (i % 2 == 0) RiderPolicy.ALLOW else RiderPolicy.SKIP).passThrough(i % 10 == 0).build())
        }
        val riders = players.take(70)
        val heading = DoubleArray(70) { r.nextDouble() * Math.PI * 2 }
        val pos = Array(70) { doubleArrayOf(r.nextDouble() * 10_000, r.nextDouble() * 10_000) }
        for ((i, p) in riders.withIndex()) {
            p.teleport(Location(world, pos[i][0], 64.0, pos[i][1]))
            val boat = world.spawn(p.location, Boat::class.java)
            boat.addPassenger(p)
        }
        server.scheduler.performOneTick() // mounts apply on the next tick
        assertEquals(70, service.stats().mountedPlayers)
        fun move() {
            for ((i, p) in riders.withIndex()) {
                pos[i][0] = (pos[i][0] + cos(heading[i]) * 2.0).mod(10_000.0)
                pos[i][1] = (pos[i][1] + sin(heading[i]) * 2.0).mod(10_000.0)
                p.setLocation(Location(world, pos[i][0], 64.0, pos[i][1]))
            }
        }
        repeat(2_000) { move(); server.scheduler.performOneTick() }
        val polls0 = service.counters()["polls.mounted"]!!
        var nanos = 0L
        val ticks = 5_000
        repeat(ticks) {
            move()
            val t0 = System.nanoTime()
            server.scheduler.performOneTick()
            nanos += System.nanoTime() - t0
        }
        val polls = service.counters()["polls.mounted"]!! - polls0
        val usPerTick = nanos / 1_000.0 / ticks
        println("ServiceCostTest ticker with 70 riders and 1,000 areas: ${"%.1f".format(usPerTick)} us/tick " +
            "(MockBukkit scheduler included), ${polls / ticks} rider polls/tick")
        assertEquals(70L * ticks, polls)
        assertTrue(usPerTick < 1_000, "ticker: $usPerTick us/tick")
    }
}
