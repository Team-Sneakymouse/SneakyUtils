package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.MarkerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * A marker whose chunk unloaded and loaded again is a new entity object with the same UUID; the
 * manager's old reference is no longer valid. Undoing the change must still remove the marker, so
 * it does not restore the block a second time when its entities load.
 */
class AlteredBlockMarkerReloadTest {

    private lateinit var server: ServerMock
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, 64)
        server.addWorld(world)
        world.getChunkAt(0, 0)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun markers(): List<Entity> = world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }

    /** Unloads [marker] with its chunk (the old object goes invalid) and returns the reloaded copy, not yet announced. */
    private fun reload(marker: Entity): Entity {
        val pdc: PersistentDataContainer = marker.persistentDataContainer
        val location = marker.location
        val tags = marker.scoreboardTags.toSet()
        marker.remove()
        val copy = MarkerMock(server, marker.uniqueId)
        copy.setLocation(location)
        tags.forEach { copy.addScoreboardTag(it) }
        pdc.copyTo(copy.persistentDataContainer, true)
        server.registerEntity(copy)
        return copy
    }

    @Test
    fun undoAfterMarkerReloadRemovesTheReloadedMarker() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false)
        val reloaded = reload(markers().single())
        server.pluginManager.callEvent(EntitiesLoadEvent(world.getChunkAt(0, 0), listOf(reloaded)))
        assertTrue(reloaded.isValid) // the block is still tracked, so the marker stays

        change.undo(false)
        assertEquals(Material.STONE, block.type)
        assertFalse(reloaded.isValid)
        assertTrue(markers().isEmpty())
    }

    @Test
    fun timedRestoreAfterMarkerReloadRemovesEveryMarker() {
        // The live smoke test: ten timed changes, chunk reloaded, then the timed restore.
        val manager = SneakyUtils.getAlteredBlockManager()
        val blocks = (0 until 10).map { world.getBlockAt(it, 60, 2) }
        for (block in blocks) manager.apply("t", block, Material.AIR, false, 5L)
        val reloaded = markers().map { reload(it) }
        server.pluginManager.callEvent(EntitiesLoadEvent(world.getChunkAt(0, 0), reloaded))
        assertEquals(10, markers().size)

        server.scheduler.performTicks(5)
        assertTrue(blocks.all { it.type == Material.STONE })
        assertTrue(markers().isEmpty())

        // A block placed later is not overwritten when the chunk's entities load again.
        blocks[0].type = Material.DIAMOND_BLOCK
        server.pluginManager.callEvent(ChunkLoadEvent(world.getChunkAt(0, 0), false))
        assertEquals(Material.DIAMOND_BLOCK, blocks[0].type)
    }

    @Test
    fun markerRemovedWhileUnloadedDoesNotRestoreAgainWhenItLoads() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false)
        val marker = markers().single()
        // Unloaded with its chunk and not loaded again yet when the change is undone.
        val pdc = marker.persistentDataContainer
        val location = marker.location
        val tags = marker.scoreboardTags.toSet()
        marker.remove()
        change.undo(false)
        assertEquals(Material.STONE, block.type)
        block.type = Material.GLASS // a player builds there afterwards

        // The chunk's entities load: the old marker comes back.
        val copy = MarkerMock(server, marker.uniqueId)
        copy.setLocation(location)
        tags.forEach { copy.addScoreboardTag(it) }
        pdc.copyTo(copy.persistentDataContainer, true)
        server.registerEntity(copy)
        server.pluginManager.callEvent(EntitiesLoadEvent(world.getChunkAt(0, 0), listOf(copy)))
        server.pluginManager.callEvent(ChunkLoadEvent(world.getChunkAt(0, 0), false))

        assertEquals(Material.GLASS, block.type) // not restored a second time
        assertFalse(copy.isValid)
        assertTrue(markers().isEmpty())
    }

    @Test
    fun aMarkerThatLoadsShortlyAfterTheUndoIsRemovedByTheRetry() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false)
        val marker = markers().single()
        val pdc = marker.persistentDataContainer
        val location = marker.location
        val tags = marker.scoreboardTags.toSet()
        marker.remove() // unloaded with its chunk
        change.undo(false)
        server.scheduler.performTicks(3)

        // Its entities load a few ticks later; no load event reaches the listener in this test.
        val copy = MarkerMock(server, marker.uniqueId)
        copy.setLocation(location)
        tags.forEach { copy.addScoreboardTag(it) }
        pdc.copyTo(copy.persistentDataContainer, true)
        server.registerEntity(copy)
        server.scheduler.performTicks(2)
        assertFalse(copy.isValid)
        assertTrue(markers().isEmpty())
    }

    @Test
    fun aDeletedMarkerIsForgottenWhenTheRetryGivesUp() {
        // An admin or cleanup plugin deleted the marker while its chunk stayed loaded: it can never
        // load again, so its UUID must not stay remembered.
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false)
        markers().single().remove()
        change.undo(false)
        assertEquals(1, manager.removedMarkerCount)
        server.scheduler.performTicks(100)
        assertEquals(0, manager.removedMarkerCount)
    }

    /** Applies a change in chunk (5, 5), then unloads that chunk with its marker. */
    private fun changeInUnloadedChunk(manager: AlteredBlockManager, z: Int): AlteredBlockManager.Change {
        world.getChunkAt(5, 5)
        val change = manager.apply("m", world.getBlockAt(80, 60, 80 + z), Material.AIR, false)
        markers().filter { it.location.blockZ == 80 + z }.single().remove()
        return change
    }

    @Test
    fun aMarkerInAnUnloadedChunkStaysRememberedAfterTheRetry() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val change = changeInUnloadedChunk(manager, 0)
        world.unloadChunk(5, 5)
        change.undo(false)
        server.scheduler.performTicks(100)
        assertEquals(1, manager.removedMarkerCount) // it can still load: keep discarding it then
    }

    @Test
    fun rememberedMarkersAreCappedOldestFirst() {
        val manager = SneakyUtils.getAlteredBlockManager()
        manager.removedMarkerCap = 2
        val changes = (0 until 3).map { changeInUnloadedChunk(manager, it) }
        world.unloadChunk(5, 5)
        for (change in changes) change.undo(false)
        assertEquals(2, manager.removedMarkerCount)
    }

    @Test
    fun crashOrphanMarkerIsStillRestored() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 1)
        manager.apply("m", block, Material.AIR, false)
        val marker = markers().single()
        // After a crash nothing is tracked: a fresh manager (as the listener would see) restores it.
        AlteredBlockManager(server.pluginManager.getPlugin("SneakyUtils") as SneakyUtils).restoreFromMarker(marker)
        assertEquals(Material.STONE, block.type)
        assertFalse(marker.isValid)
    }
}
