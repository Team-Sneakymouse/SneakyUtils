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
        assertEquals(1, server.scheduler.pendingTasks.count { it.owner === plugin })
        server.scheduler.performTicks(2)
        assertFalse(manager.isTracked(block))
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
}
