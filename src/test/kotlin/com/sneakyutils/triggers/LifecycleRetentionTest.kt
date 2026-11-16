package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.AreaSub
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Boat
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * What the service keeps after players and plugins come and go: every per-player structure must return to empty, and
 * nothing may keep a disabled plugin's handlers reachable.
 */
class LifecycleRetentionTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var service: TriggerService

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        service = SneakyUtils.getTriggers() as TriggerService
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun thousandsOfJoinQuitCyclesLeaveNothingBehind() {
        val inside = service.area(owner, Area.box(world, 100, 0, -50, 150, 255, 50), object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                // A teleport inside a teleport event: the service schedules a resync.
                if (cause == AreaCause.TELEPORT) player.teleport(Location(world, 120.5, 64.0, 1.5))
            }
        }, AreaOptions.builder().riders(RiderPolicy.ALLOW).stepEvents(true).build()) as AreaSub
        val everyone = service.everyForPlayers(owner, 1, { }, PlayerScope.ALL_ONLINE)
        val chosen = service.everyForPlayers(owner, 5, { })
        service.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), EntityClickHandler { _, _, _, _ -> false })
        val stand = world.spawn(Location(world, 100.5, 64.0, 0.5), ArmorStand::class.java)
        val fixed = service.stats().byKind.values.sum()

        repeat(2_000) { i ->
            val p = server.addPlayer("p$i")
            p.teleport(Location(world, 100.5 + i % 7, 64.0, 0.5))
            service.watchSteps(owner, p, { _, _, _, _ -> })
            service.watchBlock(owner, p, BlockOffset.BELOW, setOf(Material.STONE), { _, _, _ -> })
            chosen.add(p)
            server.pluginManager.callEvent(PlayerInteractAtEntityEvent(p, stand, Vector(), EquipmentSlot.HAND))
            if (i % 3 == 0) world.spawn(p.location, Boat::class.java).addPassenger(p)
            p.simulatePlayerMove(Location(world, 103.5 + i % 5, 64.0, 2.5))
            if (i % 10 == 0) server.scheduler.performOneTick()
            p.disconnect()
        }
        server.scheduler.performTicks(3)

        val core = service.core
        assertEquals(0, core.playerCount, "player states")
        assertEquals(0, core.watchedPlayerCount, "watch lists")
        assertEquals(0, core.mountedCount, "mounted list")
        assertTrue(core.snapshotMounted().all { it == null }, "the poll buffer holds no player")
        assertEquals(0, core.queue.pending, "queued events")
        assertEquals(0, service.clicks.pairingRecords, "entity-click pairing slots")
        assertEquals(0, service.pendingResyncCount, "resync requests")
        assertEquals(0, service.pendingRidingCount, "riding re-checks")
        assertEquals(0, everyone.size, "all-online timer")
        assertEquals(0, chosen.size, "per-player timer")
        assertEquals(0, inside.reg.members, "area members")
        assertEquals(fixed, service.stats().byKind.values.sum(), "ended watches left the registry")
        assertTrue(service.counters()["teleport.resyncs"]!! > 0, "the resync path was exercised")
        // The ticker runs while timers exist, and stops itself once nothing needs it.
        everyone.close()
        chosen.close()
        server.scheduler.performTicks(2)
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner.name == "SneakyUtils" }, "no task left behind")
    }

    @Test
    fun aDisabledPluginsAreasAreNotHeldByPlayersStandingStill() {
        // A player who doesn't move keeps their chunk's area array cached until their next block step. After a
        // plugin disables, that cache must not keep its areas (and through them its handlers and classes) reachable.
        val other = MockBukkit.createMockPlugin("Other")
        val sub = service.area(other, Area.box(world, 0, 0, 0, 15, 255, 15), object : AreaHandler {}) as AreaSub
        val p = server.addPlayer("afk")
        p.teleport(Location(world, 8.5, 64.0, 8.5))
        val state = service.core.state(p.uniqueId)!!
        assertTrue(state.chunkAreas!!.any { it === sub.reg })
        server.pluginManager.disablePlugin(other)
        assertTrue(state.chunkAreas?.none { it === sub.reg } ?: true, "the idle player's cache still holds the disabled plugin's area")
        assertTrue(state.worldAreas == null, "nor the dropped world index")
    }

    @Test
    fun registrationsRefusedInsideAPredicateLeaveNothingBehind() {
        // Regression (Codex review 2 #1): the subscription joined its owner's set before the engine refused it, so a
        // predicate trying every step piled up unreachable subscriptions until the owner disabled.
        val p = server.addPlayer("p")
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        var refused = 0
        service.area(owner, Area.custom(world, 0, 0, 0, 15, 255, 15) { _, _, _ ->
            if (runCatching { service.area(owner, Area.box(world, 0, 0, 0, 1, 1, 1), object : AreaHandler {}) }.isFailure) refused++
            if (runCatching { service.watchSteps(owner, p, { _, _, _, _ -> }) }.isFailure) refused++
            true
        }, object : AreaHandler {})
        for (x in 0 until 10) p.simulatePlayerMove(Location(world, x + 0.5, 64.0, 2.5))
        assertTrue(refused >= 20, "the predicate ran and was refused: $refused")
        assertEquals(1, service.stats().byKind.values.sum(), "only the predicate's own area is registered")
        assertEquals(0, service.core.watchedPlayerCount)
    }

    @Test
    fun aPluginThatClosedEverythingItselfStillHasIdleCachesDroppedOnDisable() {
        // Regression (Codex review 2 #2): with no subscriptions left, the disable returned before refreshing caches.
        val other = MockBukkit.createMockPlugin("Other")
        val sub = service.area(other, Area.box(world, 0, 0, 0, 15, 255, 15), object : AreaHandler {}) as AreaSub
        val p = server.addPlayer("afk")
        p.teleport(Location(world, 8.5, 64.0, 8.5))
        val state = service.core.state(p.uniqueId)!!
        sub.close() // e.g. in its own onDisable
        assertTrue(state.chunkAreas!!.any { it === sub.reg }, "still cached: the player hasn't moved")
        server.pluginManager.disablePlugin(other)
        assertTrue(state.chunkAreas?.none { it === sub.reg } ?: true, "the disable dropped it")
    }

    @Test
    fun aHundredNestedTeleportsInOneTickScheduleOneResync() {
        service.area(owner, Area.box(world, 100, 0, 0, 105, 255, 5), AreaHandler.onEnter { player, _, _ ->
            player.teleport(Location(world, 300.5, 64.0, 2.5))
        })
        val players = (0 until 100).map { server.addPlayer("t$it").also { p -> p.teleport(Location(world, 0.5, 64.0, 2.5)) } }
        for (p in players) p.teleport(Location(world, 102.5, 64.0, 2.5))
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner.name == "SneakyUtils" })
        assertEquals(100, service.pendingResyncCount)
        server.scheduler.performOneTick()
        assertEquals(0, service.pendingResyncCount)
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner.name == "SneakyUtils" })
    }
}
