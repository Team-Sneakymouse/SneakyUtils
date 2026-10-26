package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
