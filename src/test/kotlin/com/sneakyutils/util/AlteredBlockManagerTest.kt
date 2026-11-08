package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

class AlteredBlockManagerTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, 64)
        server.addWorld(world)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun timedMaterialRestoresOnlyAtDeadline() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        manager.apply("timed", block, Material.AIR, false, 3L)
        server.scheduler.performTicks(2)
        assertEquals(Material.AIR, block.type)
        server.scheduler.performOneTick()
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
    }

    @Test
    fun earlyUndoCancelsScheduledTask() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val change = manager.apply("timed", block, Material.AIR, false, 3L)
        val tasks = server.scheduler.pendingTasks.filter { it.owner === plugin }
        assertEquals(1, tasks.size)
        change.undo(false)
        assertFalse(server.scheduler.isQueued(tasks.single().taskId))
        block.type = Material.DIRT
        server.scheduler.performTicks(3)
        assertEquals(Material.DIRT, block.type)
    }

    @Test
    fun timedBlockDataRestoresOriginalData() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        manager.apply("timed", block, Material.DIRT.createBlockData(), false, 2L)
        server.scheduler.performTicks(2)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
    }

    @Test
    fun timedRegisterDoesNotMutateUntilDeadline() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val data = block.blockData
        val state = block.state
        block.type = Material.DIRT
        manager.register("external", block, data, state, false, 2L)
        assertEquals(Material.DIRT, block.type)
        server.scheduler.performTicks(2)
        assertEquals(Material.STONE, block.type)
    }

    @Test
    fun timedAddRestoresSuppliedSnapshot() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val change = AlteredBlockManager.Change("external", block, block.blockData, block.state)
        block.type = Material.DIRT
        manager.add(change, false, 2L)
        assertEquals(Material.DIRT, block.type)
        server.scheduler.performTicks(2)
        assertEquals(Material.STONE, block.type)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun nonpositiveDurationsAreRejectedBeforeMutation(duration: Long) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val data = block.blockData
        val state = block.state
        val change = AlteredBlockManager.Change("invalid", block, data, state)
        val operations = listOf<() -> Unit>(
            { manager.apply("invalid", block, Material.AIR, false, duration) },
            { manager.apply("invalid", block, Material.AIR.createBlockData(), false, duration) },
            { manager.register("invalid", block, data, state, false, duration) },
            { manager.add(change, false, duration) },
        )
        for (operation in operations) {
            assertThrows(IllegalArgumentException::class.java) { operation() }
            assertEquals(Material.STONE, block.type)
            assertFalse(manager.isTracked(block))
            assertTrue(server.scheduler.pendingTasks.none { it.owner === plugin })
        }
    }

    @Test
    fun equalRecordsKeepIndependentUndoTasks() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val first = AlteredBlockManager.Change("equal", block, block.blockData, block.state)
        val second = first.copy()
        assertEquals(first, second)
        block.type = Material.DIRT
        manager.add(first, false, 2L)
        manager.add(second, false, 4L)
        second.undo(false)
        assertSame(first, manager.getByBlock(block).single())
        assertSame(first, manager.getByInternalName("equal").single())
        assertEquals(1, manager.nameIndexedBlockCount)
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner === plugin })
        server.scheduler.performTicks(2)
        assertFalse(manager.isTracked(block))
        assertTrue(manager.getByInternalName("equal").isEmpty())
        assertEquals(0, manager.nameIndexedBlockCount)
        block.type = Material.SAND
        second.undo(false)
        server.scheduler.performTicks(2)
        assertEquals(Material.SAND, block.type)
    }

    @Test
    fun staleCallbackCannotUndoReregisteredChange() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val change = manager.apply("timed", block, Material.AIR, false, 2L)
        val stale = (server.scheduler.pendingTasks.single { it.owner === plugin }
            as org.mockbukkit.mockbukkit.scheduler.ScheduledTask).runnable
        change.undo(false)
        block.type = Material.DIRT
        manager.add(change, false, 4L)
        stale!!.run()
        assertEquals(Material.DIRT, block.type)
        assertTrue(manager.isTracked(block))
        server.scheduler.performTicks(4)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun bulkUndoCancelsTimers(restoreAll: Boolean) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val a = world.getBlockAt(1, 64, 0)
        val b = world.getBlockAt(2, 64, 0)
        manager.apply("batch", a, Material.AIR, false, 2L)
        manager.apply("batch", b, Material.DIRT, false, 3L)
        val tasks = server.scheduler.pendingTasks.filter { it.owner === plugin }
        if (restoreAll) manager.restoreAll() else manager.undoAll("batch", false)
        assertEquals(Material.STONE, a.type)
        assertEquals(Material.STONE, b.type)
        tasks.forEach { assertFalse(server.scheduler.isQueued(it.taskId)) }
        a.type = Material.SAND
        b.type = Material.SAND
        server.scheduler.performTicks(3)
        assertEquals(Material.SAND, a.type)
        assertEquals(Material.SAND, b.type)
        assertFalse(manager.isTracked(a))
        assertFalse(manager.isTracked(b))
    }

    @Test
    fun untimedChangeHasNoDeadline() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        val change = manager.apply("untimed", block, Material.AIR, false)
        server.scheduler.performTicks(100)
        assertEquals(Material.AIR, block.type)
        assertTrue(server.scheduler.pendingTasks.none { it.owner === plugin })
        change.undo(false)
        assertEquals(Material.STONE, block.type)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun automaticUndoUsesRequestedPhysics(physics: Boolean) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val original = world.getBlockAt(0, 64, 0)
        val physicsCalls = mutableListOf<Boolean>()
        val block = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.block.Block::class.java.classLoader,
            arrayOf(org.bukkit.block.Block::class.java),
        ) { _, method, args ->
            if (method.name == "setBlockData" && args?.size == 2) physicsCalls.add(args[1] as Boolean)
            method.invoke(original, *(args ?: emptyArray()))
        } as org.bukkit.block.Block
        manager.apply("physics", block, Material.AIR, physics, 1L)
        physicsCalls.clear()
        server.scheduler.performOneTick()
        assertEquals(Material.STONE, block.type)
        assertEquals(listOf(physics), physicsCalls)
    }

    @Test
    fun overlappingChangesWithLifoDeadlinesRestoreOriginal() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(0, 64, 0)
        manager.apply("older", block, Material.DIRT, false, 4L)
        manager.apply("newer", block, Material.SAND, false, 2L)
        server.scheduler.performTicks(2)
        assertEquals(Material.DIRT, block.type)
        assertEquals(1, manager.getByBlock(block).size)
        server.scheduler.performTicks(2)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
        assertTrue(world.entities.none { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) })
    }

    @Test
    fun applyTracksAndUndoRestoresMaterial() {
        val block = world.getBlockAt(0, 64, 0)
        assertEquals(Material.STONE, block.type)

        val manager = SneakyUtils.getAlteredBlockManager()
        val change = manager.apply("test-spell", block, Material.AIR, false)

        assertEquals(Material.AIR, block.type)
        assertTrue(manager.isTracked(block))
        assertEquals(1, manager.getByInternalName("test-spell").size)

        change.undo(false)

        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
        assertTrue(manager.getByInternalName("test-spell").isEmpty())
    }

    @Test
    fun undoAllReversesMultipleBlocks() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val a = world.getBlockAt(1, 64, 0)
        val b = world.getBlockAt(2, 64, 0)

        manager.apply("batch", a, Material.DIRT, false)
        manager.apply("batch", b, Material.SAND, false)

        manager.undoAll("batch", false)

        assertEquals(Material.STONE, a.type)
        assertEquals(Material.STONE, b.type)
        assertFalse(manager.isTracked(a))
        assertFalse(manager.isTracked(b))
    }

    @Test
    fun restoreFromMarkerIgnoresWrongTag() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val entity = world.spawn(world.getBlockAt(3, 64, 0).location, org.bukkit.entity.Marker::class.java)
        entity.addScoreboardTag("MS_ALTERED_BLOCK")

        manager.restoreFromMarker(entity)

        assertTrue(entity.isValid)
    }

    @Test
    fun restoreAllRevertsTrackedBlocks() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(5, 64, 5)
        manager.apply("test-spell", block, Material.AIR, false)
        assertEquals(Material.AIR, block.type)

        manager.restoreAll()

        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
    }

    private fun boxNames(
        manager: AlteredBlockManager, names: Collection<String>,
        minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        inWorld: org.bukkit.World = world,
    ): Set<Triple<Int, Int, Int>> =
        manager.getBlocksInBox(inWorld, minX, minY, minZ, maxX, maxY, maxZ, names)
            .map { Triple(it.x, it.y, it.z) }.toSet()

    @Test
    fun boxQueryIsInclusiveAndFiltersByName() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val inside = listOf(Triple(0, 60, 0), Triple(4, 62, 4), Triple(2, 61, 3))
        val outside = listOf(
            Triple(-1, 60, 0), Triple(5, 60, 0), Triple(0, 59, 0),
            Triple(0, 63, 0), Triple(0, 60, -1), Triple(0, 60, 5),
        )
        for ((x, y, z) in inside + outside) manager.apply("wanted", world.getBlockAt(x, y, z), Material.AIR, false)
        manager.apply("other", world.getBlockAt(1, 60, 1), Material.AIR, false)

        assertEquals(inside.toSet(), boxNames(manager, listOf("wanted"), 0, 60, 0, 4, 62, 4))
        assertEquals(setOf(Triple(1, 60, 1)), boxNames(manager, setOf("other"), 0, 60, 0, 4, 62, 4))
        assertEquals(
            (inside + Triple(1, 60, 1)).toSet(),
            boxNames(manager, listOf("other", "wanted"), 0, 60, 0, 4, 62, 4),
        )
        assertTrue(boxNames(manager, emptyList(), 0, 60, 0, 4, 62, 4).isEmpty())
        assertTrue(boxNames(manager, listOf("wanted"), 4, 60, 0, 0, 62, 4).isEmpty())
    }

    @Test
    fun boxQuerySpansChunkBordersAndNegativeCoordinates() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val spots = listOf(
            Triple(-17, 60, -17), Triple(-16, 60, -1), Triple(-1, 60, 15),
            Triple(15, 60, 16), Triple(16, 60, 0),
        )
        for ((x, y, z) in spots) manager.apply("s", world.getBlockAt(x, y, z), Material.AIR, false)
        manager.apply("s", world.getBlockAt(40, 60, 40), Material.AIR, false)

        assertEquals(spots.toSet(), boxNames(manager, listOf("s"), -17, 60, -17, 16, 60, 16))
        assertEquals(
            setOf(Triple(-16, 60, -1), Triple(-1, 60, 15)),
            boxNames(manager, listOf("s"), -16, 0, -16, -1, 255, 15),
        )
    }

    @Test
    fun boxWiderThanIndexedAreaStillMatches() {
        val manager = SneakyUtils.getAlteredBlockManager()
        manager.apply("s", world.getBlockAt(3, 60, -3), Material.AIR, false)
        manager.apply("s", world.getBlockAt(-5000, 60, 7000), Material.AIR, false)

        val min = Int.MIN_VALUE
        val max = Int.MAX_VALUE
        assertEquals(
            setOf(Triple(3, 60, -3), Triple(-5000, 60, 7000)),
            boxNames(manager, listOf("s"), min, min, min, max, max, max),
        )
        assertEquals(setOf(Triple(3, 60, -3)), boxNames(manager, listOf("s"), -1000, 0, -1000, 1000, 255, 1000))
    }

    @Test
    fun boxQueryListsBlockWithSeveralChangesOnce() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(2, 60, 2)
        manager.apply("a", block, Material.DIRT, false)
        manager.apply("a", block, Material.SAND, false)
        manager.apply("b", block, Material.GLASS, false)

        val found = manager.getBlocksInBox(world, 0, 0, 0, 5, 100, 5, listOf("a", "b"))
        assertEquals(listOf(block), found)
    }

    @Test
    fun boxQueryKeepsWorldsApart() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val other = server.addSimpleWorld("other")
        manager.apply("s", world.getBlockAt(1, 60, 1), Material.AIR, false)
        manager.apply("s", other.getBlockAt(2, 60, 2), Material.AIR, false)

        assertEquals(setOf(Triple(1, 60, 1)), boxNames(manager, listOf("s"), 0, 0, 0, 5, 100, 5))
        assertEquals(setOf(Triple(2, 60, 2)), boxNames(manager, listOf("s"), 0, 0, 0, 5, 100, 5, other))
    }

    @Test
    fun boxQueryFollowsUndo() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val older = manager.apply("a", block, Material.DIRT, false)
        val newer = manager.apply("b", block, Material.SAND, false)
        fun found(name: String) = manager.getBlocksInBox(world, 0, 0, 0, 5, 100, 5, listOf(name)).size

        assertEquals(1, found("a"))
        assertEquals(1, found("b"))
        newer.undo(false)
        assertEquals(1, found("a"))
        assertEquals(0, found("b"))
        assertEquals(1, manager.indexedBlockCount)
        older.undo(false)
        assertEquals(0, found("a"))
        assertEquals(0, manager.indexedBlockCount)

        // A block tracked again after being fully restored is indexed again.
        manager.apply("a", block, Material.DIRT, false)
        assertEquals(1, found("a"))
        assertEquals(1, manager.indexedBlockCount)
    }

    @ParameterizedTest
    @ValueSource(strings = ["timed", "undoAll", "restoreAll"])
    fun boxQueryForgetsBlocksRestoredInBulkOrOnTimer(how: String) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val a = world.getBlockAt(1, 60, 1)
        val b = world.getBlockAt(20, 60, 20)
        if (how == "timed") {
            manager.apply("s", a, Material.AIR, false, 2L)
            manager.apply("s", b, Material.AIR, false, 2L)
        } else {
            manager.apply("s", a, Material.AIR, false)
            manager.apply("s", b, Material.AIR, false)
        }
        assertEquals(2, manager.getBlocksInBox(world, -50, 0, -50, 50, 100, 50, listOf("s")).size)

        when (how) {
            "timed" -> server.scheduler.performTicks(2)
            "undoAll" -> manager.undoAll("s", false)
            else -> manager.restoreAll()
        }
        assertTrue(manager.getBlocksInBox(world, -50, 0, -50, 50, 100, 50, listOf("s")).isEmpty())
        assertEquals(0, manager.indexedBlockCount)
        assertTrue(manager.getByInternalName("s").isEmpty())
        assertEquals(0, manager.nameIndexedBlockCount)
    }

    @Test
    fun nameLookupFiltersAcrossBlocksAndWorlds() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val other = server.addSimpleWorld("other")
        val a = manager.apply("a", world.getBlockAt(1, 60, 1), Material.AIR, false)
        val b = manager.apply("b", world.getBlockAt(2, 60, 2), Material.AIR, false)
        val far = manager.apply("a", other.getBlockAt(-900, 60, 900), Material.AIR, false)

        assertEquals(setOf(a, far), manager.getByInternalName("a").toSet())
        assertEquals(listOf(b), manager.getByInternalName("b"))
        assertTrue(manager.getByInternalName("missing").isEmpty())

        manager.undoAll("a", false)
        assertTrue(manager.getByInternalName("a").isEmpty())
        assertEquals(listOf(b), manager.getByInternalName("b"))
        assertEquals(1, manager.nameIndexedBlockCount)
    }

    @Test
    fun nameLookupFollowsPartialUndoOnMixedBlock() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val a1 = manager.apply("a", block, Material.DIRT, false)
        val b = manager.apply("b", block, Material.SAND, false)
        val a2 = manager.apply("a", block, Material.GLASS, false)

        // Oldest first within a block, as before the index.
        assertEquals(listOf(a1, a2), manager.getByInternalName("a"))
        assertEquals(2, manager.nameIndexedBlockCount)

        a2.undo(false)
        assertEquals(listOf(a1), manager.getByInternalName("a"))
        assertEquals(listOf(b), manager.getByInternalName("b"))
        assertEquals(2, manager.nameIndexedBlockCount)

        b.undo(false)
        assertTrue(manager.getByInternalName("b").isEmpty())
        assertEquals(listOf(a1), manager.getByInternalName("a"))
        assertEquals(1, manager.nameIndexedBlockCount)

        a1.undo(false)
        assertTrue(manager.getByInternalName("a").isEmpty())
        assertEquals(0, manager.nameIndexedBlockCount)
        assertEquals(Material.STONE, block.type)

        // Tracked again after a full restore: found again.
        val again = manager.apply("a", block, Material.DIRT, false)
        assertEquals(listOf(again), manager.getByInternalName("a"))
        assertEquals(1, manager.nameIndexedBlockCount)
    }

    @Test
    fun undoAllRestoresSameNameStackOnOneBlock() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(3, 60, 3)
        manager.apply("s", block, Material.DIRT, false)
        manager.apply("s", block, Material.SAND, false)

        manager.undoAll("s", false)

        assertEquals(Material.STONE, block.type)
        assertFalse(manager.isTracked(block))
        assertEquals(0, manager.nameIndexedBlockCount)
    }
}
