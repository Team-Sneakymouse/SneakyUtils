package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerDiagnostics
import com.sneakyutils.triggers.service.TriggerService
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.EntityType
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * Who a capture blames: handler time by owner, callback and label; errors, slow calls, self-triggered skips and
 * loops by the same names; engine stages around them; the name budget; and the probe coming and going.
 */
class TriggerAttributionTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var service: TriggerService
    private val probe = FakeProbe()
    private val diagnostics = FakeDiagnostics(probe)

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        val diag = MockBukkit.createMockPlugin("SneakyDiagnostics")
        server.servicesManager.register(Diagnostics::class.java, diagnostics, diag, ServicePriority.Normal)
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        service = SneakyUtils.getTriggers() as TriggerService
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun capture() {
        probe.capturing = true
        service.refreshTiming()
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun labelsAreValidatedAndKeptByEveryOptionsType() {
        for (bad in listOf("", "Holdover:x", "a b", ":x", "-x", "x".repeat(65), "holdover/forge", "é")) {
            assertThrows(IllegalArgumentException::class.java) { AreaOptions.builder().label(bad) }
            assertFalse(TriggerLabels.isValid(bad), bad)
        }
        for (good in listOf("a", "holdover:forge-bellow", "x.y_z:1-2", "9".repeat(64))) assertTrue(TriggerLabels.isValid(good), good)
        assertEquals("l", AreaOptions.builder().label("l").build().toBuilder().build().label)
        assertEquals("l", WatchOptions.builder().label("l").build().toBuilder().build().label)
        assertEquals("l", PlayerTimerOptions.builder().label("l").build().toBuilder().build().label)
        assertEquals("l", ClickOptions.builder().label("l").build().toBuilder().build().label)
        assertEquals("l", BreakOptions.builder().label("l").build().toBuilder().build().label)
        assertEquals("l", TimerOptions.builder().label("l").build().toBuilder().build().label)
        assertNull(AreaOptions.DEFAULT.label)
        assertThrows(IllegalArgumentException::class.java) { service.labelled("Bad Label") }
    }

    @Test
    fun aLabelledViewLabelsEveryKindUnlessTheOptionsSayOtherwise() {
        val view = service.labelled("owner:feature")
        val p = server.addPlayer()
        val subs = listOf(
            view.area(owner, Area.box(world, 0, 0, 0, 1, 1, 1), object : AreaHandler {}),
            view.watchSteps(owner, p) { _, _, _, _ -> },
            view.watchBlockUnder(owner, p, setOf(Material.STONE)) { _, _ -> },
            view.watchBlock(owner, p, BlockOffset.FEET, setOf(Material.WATER)) { _, _, _ -> },
            view.every(owner, 20) {},
            view.everyForPlayers(owner, 20, { }),
            view.everyForPlayers(owner, 20, { }, PlayerTimerOptions.builder().skipDead(false).build()),
            view.onBlockClick(owner, BlockKey.of(world, 1, 2, 3)) { _, _, _, _ -> false },
            view.onBlockType(owner, Material.LEVER) { _, _, _, _ -> false },
            view.onBlockBreak(owner, BlockKey.of(world, 1, 2, 3)) { _, _, _ -> },
            view.onBlockBreakType(owner, Material.STONE) { _, _, _ -> },
            view.onEntityClick(owner, EntityMatch.type(EntityType.COW)) { _, _, _, _ -> false },
        )
        for (s in subs) assertEquals("owner:feature", s.label, s.id)
        val explicit = view.area(owner, Area.box(world, 0, 0, 0, 1, 1, 1), object : AreaHandler {}, AreaOptions.builder().label("owner:other").build())
        assertEquals("owner:other", explicit.label)
        assertNull(service.every(owner, 20) {}.label)
        assertEquals("owner:b", view.labelled("owner:b").every(owner, 20) {}.label, "labels don't nest")
        assertEquals(setOf("owner:feature", "owner:other", "owner:b", null), service.stats().subscriptions.map { it.label }.toSet())
        assertSame(owner, subs[0].owner)
    }

    // ---------------------------------------------------------------- attribution

    @Test
    fun handlerTimeIsNamedByOwnerCallbackAndLabelInsideTheEngineStages() {
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        val view = service.labelled("owner:gate")
        view.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.of({ _, _, _ -> }, { _, _, _ -> }),
            AreaOptions.builder().stepEvents(true).build())
        capture()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 3.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        assertEquals(
            listOf(
                "handler.owner.area-enter.owner:gate", "drain", "move",
                "handler.owner.area-step.owner:gate", "drain", "move",
                "handler.owner.area-exit.owner:gate", "drain", "move",
            ),
            probe.sections,
        )

        probe.sections.clear()
        p.teleport(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("handler.owner.area-enter.owner:gate", "drain", "teleport"), probe.sections)

        probe.sections.clear()
        service.onBlockClick(owner, BlockKey.of(world, 1, 64, 1), { _, _, _, _ -> false }, ClickOptions.builder().label("owner:lever").build())
        service.onBlockBreakType(owner, Material.STONE, { _, _, _ -> }, BreakOptions.builder().label("owner:ore").build())
        service.onEntityClick(owner, EntityMatch.type(EntityType.COW), { _, _, _, _ -> false })
        val block = world.getBlockAt(1, 64, 1)
        server.pluginManager.callEvent(PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND))
        world.getBlockAt(2, 64, 2).type = Material.STONE
        server.pluginManager.callEvent(BlockBreakEvent(world.getBlockAt(2, 64, 2), p))
        val cow = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.COW)
        server.pluginManager.callEvent(PlayerInteractEntityEvent(p, cow, EquipmentSlot.HAND))
        assertEquals(
            listOf(
                "handler.owner.block-click.owner:lever", "click.block",
                "handler.owner.block-break-type.owner:ore", "click.break",
                "handler.owner.entity-click", "click.entity",
            ),
            probe.sections,
        )

        probe.sections.clear()
        service.everyForPlayers(owner, 1, { }, PlayerTimerOptions.builder().label("owner:tick").build()).add(p)
        server.scheduler.performOneTick()
        assertEquals(listOf("handler.owner.player-timer.owner:tick", "tick.timers", "tick"), probe.sections)
        assertEquals(0, probe.nestingErrors)
    }

    @Test
    fun aSlowOrThrowingHandlerIsNamedInTheCounts() {
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        service.labelled("owner:slow").area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ ->
            val until = System.nanoTime() + 2_000_000
            while (System.nanoTime() < until) Thread.onSpinWait()
        })
        service.labelled("owner:bad").area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ ->
            throw IllegalStateException("broken on purpose")
        })
        capture()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(1L, probe.counts["handler.slow.owner.area-enter.owner:slow"])
        assertEquals(1L, probe.counts["handler.errors.owner.area-enter.owner:bad"])
        assertNull(probe.counts["handler.slow.owner.area-enter.owner:bad"])
        val stats = service.stats().subscriptions.associateBy { it.label }
        assertEquals(1L, stats["owner:slow"]!!.slow)
        assertEquals(1L, stats["owner:bad"]!!.errors)
        assertEquals(1L, service.counters()["handler.slow"])
    }

    @Test
    fun selfTriggeredStepsAreCountedForTheSubscriptionThatCausedThem() {
        val p = server.addPlayer()
        p.teleport(Location(world, 0.5, 64.0, 0.5))
        var calls = 0
        service.watchSteps(owner, p, { _, _, to, _ ->
            calls++
            // A jump pad: moves its own player on, which the re-entry guard keeps from calling it again.
            p.simulatePlayerMove(Location(world, to.x + 5.5, 64.0, 0.5))
        }, WatchOptions.builder().label("owner:pad").build())
        capture()
        p.simulatePlayerMove(Location(world, 1.5, 64.0, 0.5))
        assertEquals(1, calls)
        assertEquals(1L, probe.counts["handler.self-skipped.owner.step-watch.owner:pad"])
        assertEquals(0, probe.nestingErrors)
    }

    @Test
    fun tooManyLabelsFoldIntoTheOwnerThenIntoOtherAndAreFlagged() {
        val owners = (0 until 40).map { MockBukkit.createMockPlugin("Plugin$it") }
        for (o in owners) repeat(8) { i ->
            service.labelled("l$i").area(o, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> })
        }
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        capture()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        val names = probe.sections.toSet()
        val handlerNames = names.filter { it.startsWith("handler.") }
        assertEquals(320, probe.sections.count { it.startsWith("handler.") }, "every call is recorded under some name")
        assertTrue(handlerNames.size <= TriggerDiagnostics.SECTION_CAP + 1, "${handlerNames.size} handler sections")
        assertTrue(names.size < 256 - 13, "${names.size} sections leave room for the engine stages")
        assertEquals(TriggerDiagnostics.SECTION_PRIMARY, handlerNames.count { it.count { c -> c == '.' } == 3 }, "labelled names admitted")
        assertTrue("handler.plugin39.area-enter" in names, "folded into the owner name")
        assertEquals(320L - TriggerDiagnostics.SECTION_PRIMARY, probe.counts[TriggerDiagnostics.FOLDED])
        assertTrue(names.all { it.length <= 192 })

        // A new capture starts a new budget.
        probe.capturing = false
        service.refreshTiming()
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        probe.sections.clear()
        probe.counts.clear()
        capture()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(320L - TriggerDiagnostics.SECTION_PRIMARY, probe.counts[TriggerDiagnostics.FOLDED])
    }

    @Test
    fun subscriptionLevelsByKindAndOwnerAreSampledDuringACapture() {
        service.labelled("owner:x").every(owner, 20) {}
        service.area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), object : AreaHandler {})
        val other = MockBukkit.createMockPlugin("Other")
        service.everyForPlayers(other, 20, { }).add(server.addPlayer())
        probe.capturing = true
        probe.due = true
        server.scheduler.performTicks(20)
        assertEquals(1L, probe.levels["subscriptions.by-kind.area"])
        assertEquals(1L, probe.levels["subscriptions.by-kind.timer"])
        assertEquals(0L, probe.levels["subscriptions.by-kind.entity-click"])
        assertEquals(1L, probe.levels["subscriptions.by-owner.owner.timer"])
        assertEquals(1L, probe.levels["subscriptions.by-owner.other.player-timer"])
        assertEquals(1L, probe.levels["timers.per-player.players"])
        assertNull(probe.levels["subscriptions.by-owner.other"], "no overflow")
    }

    @Test
    fun keepsWorkingWhenSneakyDiagnosticsGoesAwayAndComesBack() {
        val p = server.addPlayer()
        p.teleport(Location(world, 20.5, 64.0, 2.5))
        var enters = 0
        service.labelled("owner:gate").area(owner, Area.box(world, 0, 60, 0, 5, 70, 5), AreaHandler.onEnter { _, _, _ -> enters++ })
        capture()
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertTrue("handler.owner.area-enter.owner:gate" in probe.sections)

        // SneakyDiagnostics disabled mid-capture: the shim drops the probe; triggers carry on unmeasured.
        server.servicesManager.unregisterAll(server.pluginManager.getPlugin("SneakyDiagnostics")!!)
        probe.sections.clear()
        val starts = probe.startCalls
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        server.scheduler.performTicks(40)
        assertEquals(2, enters)
        assertEquals(starts, probe.startCalls, "nothing reaches the old probe")
        assertFalse(service.timing)

        // Enabled again: a fresh handle, measured again.
        val fresh = FakeProbe().also { it.capturing = true }
        server.servicesManager.register(Diagnostics::class.java, FakeDiagnostics(fresh), MockBukkit.createMockPlugin("SneakyDiagnostics2"), ServicePriority.Normal)
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(3, enters)
        assertTrue("handler.owner.area-enter.owner:gate" in fresh.sections, fresh.sections.toString())
        assertEquals(0, fresh.nestingErrors)
    }
}
