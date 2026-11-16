package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.io.File

class TablesTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers
    private lateinit var player: PlayerMock
    private val log = ArrayList<String>()

    @TempDir
    lateinit var dir: File

    /** Resolves `door`, `ore`, `forge`; `explode` throws; anything else is unknown. Records the args it saw. */
    private val resolver = object : ActionResolver {
        val args = ArrayList<Map<String, Any?>>()

        override fun blockClick(action: String, args: Map<String, Any?>): BlockClickHandler? {
            this.args += args
            if (action == "explode") error("no such door")
            return if (action == "door") BlockClickHandler { _, b, _, _ -> log += "door ${b.x}"; false } else null
        }

        override fun blockBreak(action: String, args: Map<String, Any?>): BlockBreakHandler? =
            if (action == "ore") BlockBreakHandler { _, b, _ -> log += "ore ${b.x}" } else null

        override fun area(action: String, args: Map<String, Any?>): AreaHandler? = if (action != "forge") null else
            object : AreaHandler {
                override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                    log += "forge enter $cause"
                }
            }
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
        player = server.addPlayer("p")
        player.teleport(Location(world, 20.5, 64.0, 2.5))
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun click(x: Int, y: Int, z: Int) {
        server.pluginManager.callEvent(
            PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, world.getBlockAt(x, y, z), BlockFace.UP, EquipmentSlot.HAND),
        )
    }

    private fun table(text: String): File = File(dir, "buttons.yml").also { it.writeText(text.trimIndent()) }

    @Test
    fun loadsValidEntriesAndReportsEachRejectedOneWithItsLine() {
        val f = table(
            """
            - {kind: block-click, world: world, at: [10, 64, -3], side: right, action: door, args: {code: 1234, open: true, tags: [a, b], name: "x"}}
            - {kind: block-break, world: world, at: [11, 64, -3], action: ore}
            - {kind: area, world: world, min: [0, 60, 0], max: [5, 70, 5], riders: allow, action: forge}
            - {kind: block-click, world: world, at: [1, 2, 3], action: unknown-thing}
            - {kind: block-click, world: world, at: [1, 2, 3], sid: right, action: door}
            - {kind: block-click, world: nowhere, at: [1, 2, 3], action: door}
            - {kind: block-click, world: world, at: [1, 2], action: door}
            - {kind: area, world: world, min: [0, 0, 0], max: [5000, 10, 5], action: forge}
            - {kind: block-click, world: world, at: [1, 2, 3], action: explode}
            - {kind: teleporter, world: world, action: door}
            """,
        )
        val r = triggers.loadTable(owner, f, resolver)
        assertTrue(r.replacedPrevious)
        assertEquals(listOf(1, 2, 3), r.loaded.map { it.line })
        assertEquals(listOf("block-click", "block-break", "area"), r.loaded.map { it.kind })
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10), r.rejected.map { it.line })
        val reasons = r.rejected.map { it.reason }
        assertTrue(reasons[0].contains("unknown action 'unknown-thing'"), reasons[0])
        assertTrue(reasons[1].contains("unknown key 'sid'"), reasons[1])
        assertTrue(reasons[2].contains("world 'nowhere'"), reasons[2])
        assertTrue(reasons[3].contains("[x, y, z]"), reasons[3])
        assertTrue(reasons[4].contains("chunks"), reasons[4])
        assertTrue(reasons[5].contains("no such door"), reasons[5])
        assertTrue(reasons[6].contains("unknown kind"), reasons[6])

        assertEquals(mapOf("code" to 1234, "open" to true, "tags" to listOf("a", "b"), "name" to "x"), resolver.args[0])
        click(10, 64, -3)
        player.simulateBlockBreak(world.getBlockAt(11, 64, -3))
        player.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("door 10", "ore 11", "forge enter MOVE"), log)
    }

    @Test
    fun reloadingTheSameFileReplacesItsSubscriptions() {
        val f = table("- {kind: block-click, world: world, at: [10, 64, -3], action: door}")
        val first = triggers.loadTable(owner, f, resolver)
        val second = triggers.loadTable(owner, f, resolver)
        assertFalse(first.subscriptions.single().active)
        assertTrue(second.subscriptions.single().active)
        click(10, 64, -3)
        assertEquals(listOf("door 10"), log, "once, not twice")

        f.writeText("- {kind: block-click, world: world, at: [20, 64, -3], action: door}")
        triggers.loadTable(owner, f, resolver)
        log.clear()
        click(10, 64, -3)
        click(20, 64, -3)
        assertEquals(listOf("door 20"), log)
    }

    @Test
    fun anUnreadableFileKeepsThePreviousLoad() {
        val f = table("- {kind: block-click, world: world, at: [10, 64, -3], action: door}")
        val good = triggers.loadTable(owner, f, resolver)
        f.writeText("- {kind: block-click, world: world\n- [unclosed")
        val bad = triggers.loadTable(owner, f, resolver)
        assertFalse(bad.replacedPrevious)
        assertTrue(bad.loaded.isEmpty())
        assertEquals(1, bad.rejected.size)
        assertTrue(bad.rejected[0].line >= 1, "the YAML error has a line")
        assertTrue(good.subscriptions.single().active, "the previous load stays")
        val missing = triggers.loadTable(owner, File(dir, "nope.yml"), resolver)
        assertFalse(missing.replacedPrevious)
        assertEquals(0, missing.rejected.single().line)
    }

    @Test
    fun aMapWithEntriesAndAnEmptyFileAreAccepted() {
        val f = table(
            """
            entries:
              - kind: block-click
                world: world
                at: [10, 64, -3]
                action: door
                cancel: true
            """,
        )
        val r = triggers.loadTable(owner, f, resolver)
        assertEquals(listOf(2), r.loaded.map { it.line })
        f.writeText("")
        val empty = triggers.loadTable(owner, f, resolver)
        assertTrue(empty.replacedPrevious)
        assertFalse(r.subscriptions.single().active, "an empty table replaces the previous one with nothing")
    }

    @Test
    fun recursiveAndExplodingAliasesRejectOnlyTheirEntry() {
        // Regression (Codex M4 #1): a recursive alias overflowed the stack and aborted the whole load.
        val f = table(
            """
            - {kind: block-click, world: world, at: [10, 64, -3], action: door, args: &a {self: *a}}
            - {kind: block-click, world: world, at: [11, 64, -3], action: door}
            - kind: block-click
              world: world
              at: [12, 64, -3]
              action: door
              args:
                a0: &a0 [1, 1]
                a1: &a1 [*a0, *a0]
                a2: &a2 [*a1, *a1]
                a3: &a3 [*a2, *a2]
                a4: &a4 [*a3, *a3]
                a5: &a5 [*a4, *a4]
                a6: &a6 [*a5, *a5]
                a7: &a7 [*a6, *a6]
                a8: &a8 [*a7, *a7]
                a9: &a9 [*a8, *a8]
                a10: &a10 [*a9, *a9]
                a11: &a11 [*a10, *a10]
                a12: &a12 [*a11, *a11]
                a13: &a13 [*a12, *a12]
                a14: &a14 [*a13, *a13]
                a15: &a15 [*a14, *a14]
                a16: &a16 [*a15, *a15]
                a17: &a17 [*a16, *a16]
                a18: &a18 [*a17, *a17]
                a19: &a19 [*a18, *a18]
                a20: &a20 [*a19, *a19]
                a21: &a21 [*a20, *a20]
                a22: &a22 [*a21, *a21]
                a23: &a23 [*a22, *a22]
            """,
        )
        val r = triggers.loadTable(owner, f, resolver)
        assertTrue(r.replacedPrevious, r.rejected.toString())
        assertEquals(listOf(2), r.loaded.map { it.line })
        assertEquals(listOf(1, 3), r.rejected.map { it.line })
        assertTrue(r.rejected[0].reason.contains("recursive"), r.rejected[0].reason)
        assertTrue(r.rejected[1].reason.contains("too large"), r.rejected[1].reason)
    }

    @Test
    fun numbersAreDecimalOrRejectedNeverSilentlyChanged() {
        // Regression (Codex M4 #2): 010 became 10 (YAML means octal 8) and 0x10 became a string.
        val f = table(
            """
            - {kind: block-click, world: world, at: [010, 64, -3], action: door}
            - {kind: block-click, world: world, at: [0x10, 64, -3], action: door}
            - {kind: block-click, world: world, at: [10, 64, -3], action: door, args: {n: 0b101}}
            - {kind: block-click, world: world, at: [-10, +64, 1_000], action: door, args: {f: 1.5, big: 3000000000}}
            """,
        )
        val r = triggers.loadTable(owner, f, resolver)
        assertEquals(listOf(4), r.loaded.map { it.line })
        assertEquals(listOf(1, 2, 3), r.rejected.map { it.line })
        for (rej in r.rejected) assertTrue(rej.reason.contains("decimal"), rej.reason)
        assertEquals(mapOf("f" to 1.5, "big" to 3_000_000_000L), resolver.args.last())
        click(-10, 64, 1000)
        assertEquals(listOf("door -10"), log)
    }

    @Test
    fun reloadingTheSameTableFromAHandlerFiredByTheReplaceLeavesOneSet() {
        // Regression (Codex full review #3): the outer replace's close fired an exit whose handler reloaded the same
        // table; both sets stayed registered and later reloads closed only one of them.
        val f = table(
            """
            - {kind: area, world: world, min: [0, 60, 0], max: [5, 70, 5], fire-on-register: true, action: reload}
            - {kind: block-click, world: world, at: [10, 64, -3], action: door}
            """,
        )
        val nested = ArrayList<TableLoadResult>()
        val reloading = object : ActionResolver {
            override fun blockClick(action: String, args: Map<String, Any?>) = resolver.blockClick(action, args)
            override fun area(action: String, args: Map<String, Any?>): AreaHandler = object : AreaHandler {
                override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                    if (cause == AreaCause.REGISTER) nested += triggers.loadTable(owner, f, resolver)
                }

                override fun onExit(player: Player, area: Area, cause: AreaCause) {
                    nested += triggers.loadTable(owner, f, resolver)
                }
            }
        }
        triggers.loadTable(owner, f, reloading)
        player.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        nested.clear()
        val outer = triggers.loadTable(owner, f, reloading)
        assertTrue(outer.replacedPrevious)
        assertTrue(nested.isNotEmpty(), "the replace fired handlers that tried to reload")
        assertTrue(nested.none { it.replacedPrevious }, "a nested reload of the same table is refused")
        assertTrue(nested.all { it.rejected.single().reason.contains("already being loaded") }, nested.toString())
        assertEquals(2, triggers.stats().byOwner["Owner"]!!.values.sum(), "one set: one area and one click")
        click(10, 64, -3)
        assertEquals(listOf("door 10"), log)
        // A later reload replaces everything (the plain resolver doesn't know 'reload', so only the click is left).
        triggers.loadTable(owner, f, resolver)
        assertEquals(1, triggers.stats().byOwner["Owner"]!!.values.sum())
    }

    @Test
    fun blockBreakEntriesCanAskForCancelledBreaks() {
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOW)
            fun protect(e: org.bukkit.event.block.BlockBreakEvent) {
                e.isCancelled = true
            }
        }, owner)
        val f = table(
            """
            - {kind: block-break, world: world, at: [11, 64, -3], action: ore, ignore-cancelled: false}
            - {kind: block-break, world: world, at: [12, 64, -3], action: ore}
            - {kind: block-break, world: world, at: [13, 64, -3], action: ore, ignore-cancelled: maybe}
            """,
        )
        val r = triggers.loadTable(owner, f, resolver)
        assertEquals(listOf(1, 2), r.loaded.map { it.line })
        assertEquals(listOf(3), r.rejected.map { it.line })
        player.simulateBlockBreak(world.getBlockAt(11, 64, -3))
        player.simulateBlockBreak(world.getBlockAt(12, 64, -3))
        assertEquals(listOf("ore 11"), log, "only the entry that asked sees the protected break")
    }

    @Test
    fun ownerDisableClosesTableSubscriptions() {
        val f = table("- {kind: block-click, world: world, at: [10, 64, -3], action: door}")
        val r = triggers.loadTable(owner, f, resolver)
        server.pluginManager.disablePlugin(owner)
        assertFalse(r.subscriptions.single().active)
    }
}
