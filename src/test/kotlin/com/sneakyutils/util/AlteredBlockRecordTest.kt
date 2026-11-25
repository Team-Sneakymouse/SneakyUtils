package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * The crash fallback is one record per altered block in its chunk's PDC (saved in the same chunk
 * write as the block), not a marker entity. Records are restored as their chunk loads, unless the
 * block is tracked in this session.
 */
class AlteredBlockRecordTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, -64, 320, 64) // min, max, surface height
        server.addWorld(world)
        world.getChunkAt(0, 0)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private val manager get() = SneakyUtils.getAlteredBlockManager()

    private fun markers() = world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }

    private fun recordKeys(block: Block) =
        block.chunk.persistentDataContainer.keys.filter { it.key.startsWith("altered_block_at_") }

    private fun loadChunk(block: Block) = server.pluginManager.callEvent(ChunkLoadEvent(block.chunk, false))

    @Test
    fun applyWritesOneRecordAndNoMarker() {
        val blocks = (0 until 20).map { world.getBlockAt(it % 16, 60, it / 16) }
        for (block in blocks) manager.apply("bomb", block, Material.AIR, false, AlteredBlockCauses.EXPLOSION)
        assertTrue(markers().isEmpty())
        assertEquals(20, recordKeys(blocks[0]).size)
        assertTrue(blocks.all { manager.hasRecord(it) })
    }

    @Test
    fun undoRemovesTheRecord() {
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false)
        change.undo(false)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.hasRecord(block))
        assertTrue(recordKeys(block).isEmpty())
    }

    @Test
    fun stackedChangesShareOneRecordHoldingTheOriginal() {
        val block = world.getBlockAt(1, 60, 1)
        manager.apply("a", block, Material.DIRT, false)
        manager.apply("b", block, Material.SAND, false)
        assertEquals(1, recordKeys(block).size)

        AlteredBlockManager(plugin).restoreChunk(block.chunk) // a crash: nothing tracked
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.hasRecord(block))
    }

    @Test
    fun aCrashRecordIsRestoredWhenItsChunkLoads() {
        val block = world.getBlockAt(3, -12, 9) // below y 0
        // Written by another manager (the session that crashed): the plugin's manager tracks nothing.
        AlteredBlockManager(plugin).apply("m", block, Material.GLASS, false)
        loadChunk(block)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.hasRecord(block))
    }

    @Test
    fun aTrackedBlocksRecordIsLeftAloneWhenItsChunkLoadsAgain() {
        val block = world.getBlockAt(1, 60, 1)
        val change = manager.apply("m", block, Material.AIR, false, 5L)
        loadChunk(block) // the chunk unloaded and loaded again while the change is live
        assertEquals(Material.AIR, block.type)
        assertTrue(manager.hasRecord(block))

        server.scheduler.performTicks(5)
        assertEquals(Material.STONE, block.type)
        assertFalse(manager.hasRecord(block))
        assertFalse(manager.isTracked(block))
        assertTrue(manager.getByBlock(block).none { it === change })
    }

    @Test
    fun aBlockBuiltAfterTheUndoIsNotOverwrittenWhenItsChunkLoads() {
        val block = world.getBlockAt(1, 60, 1)
        manager.apply("m", block, Material.AIR, false).undo(false)
        block.type = Material.DIAMOND_BLOCK
        loadChunk(block)
        assertEquals(Material.DIAMOND_BLOCK, block.type)
    }

    @Test
    fun restoreLoadedChunksRecoversChunksLoadedBeforeEnable() {
        val a = world.getBlockAt(1, 60, 1)
        val b = world.getBlockAt(40, 60, 40)
        manager.apply("m", a, Material.AIR, false)
        manager.apply("m", b, Material.AIR, false)
        AlteredBlockManager(plugin).restoreLoadedChunks()
        assertEquals(Material.STONE, a.type)
        assertEquals(Material.STONE, b.type)
    }

    @Test
    fun aMalformedRecordIsDropped() {
        val chunk = world.getChunkAt(0, 0)
        val pdc = chunk.persistentDataContainer
        pdc.set(NamespacedKey(plugin, "altered_block_at_99_60_1"), PersistentDataType.STRING, "junk")
        pdc.set(NamespacedKey(plugin, "unrelated"), PersistentDataType.STRING, "kept")
        manager.restoreChunk(chunk)
        assertTrue(recordKeys(chunk.getBlock(0, 60, 0)).isEmpty())
        assertEquals("kept", pdc.get(NamespacedKey(plugin, "unrelated"), PersistentDataType.STRING))
    }

    @Test
    fun aLegacyMarkerFromACrashIsStillRestoredWhenItsEntitiesLoad() {
        val block = world.getBlockAt(2, 60, 2)
        block.type = Material.AIR
        val marker = world.spawnEntity(block.location.add(0.5, 0.5, 0.5), org.bukkit.entity.EntityType.MARKER)
        marker.addScoreboardTag(SneakyUtils.ALTERED_BLOCK_TAG)
        val pdc = marker.persistentDataContainer
        pdc.set(NamespacedKey(plugin, "altered_block_format"), PersistentDataType.INTEGER, 2)
        pdc.set(NamespacedKey(plugin, "altered_block_data"), PersistentDataType.STRING, "minecraft:stone")
        pdc.set(NamespacedKey(plugin, "altered_block_world"), PersistentDataType.STRING, world.uid.toString())
        pdc.set(NamespacedKey(plugin, "altered_block_x"), PersistentDataType.INTEGER, 2)
        pdc.set(NamespacedKey(plugin, "altered_block_y"), PersistentDataType.INTEGER, 60)
        pdc.set(NamespacedKey(plugin, "altered_block_z"), PersistentDataType.INTEGER, 2)

        server.pluginManager.callEvent(EntitiesLoadEvent(block.chunk, listOf(marker)))
        assertEquals(Material.STONE, block.type)
        assertFalse(marker.isValid)
    }
}
