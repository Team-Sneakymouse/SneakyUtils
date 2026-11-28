package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.world.ChunkLoadEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

class BulkChangeTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, -64, 320, 64)
        server.addWorld(world)
        for (cx in -1..1) for (cz in -1..1) world.getChunkAt(cx, cz)
        plugin.config.set("altered-blocks.bulk-blocks-per-tick", 10)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private val manager get() = SneakyUtils.getAlteredBlockManager()

    /** A line of [n] stone blocks at y 60 from x 0 along +x, all made air (a crater from x 0 outwards). */
    private fun line(n: Int, name: String = "crater", restoreAfter: Long = 0): Pair<BulkChange, List<Block>> {
        val blocks = (0 until n).map { world.getBlockAt(it, 60, 0) }
        val bulk = manager.bulk(name, world, AlteredBlockCauses.EXPLOSION).target { Material.AIR.createBlockData() }
        if (restoreAfter > 0) bulk.restoreAfter(restoreAfter)
        for (b in blocks) bulk.add(b.x, b.y, b.z)
        bulk.start()
        return bulk to blocks
    }

    private fun bulkRecords(block: Block) =
        block.chunk.persistentDataContainer.keys.filter { it.key.startsWith("altered_bulk_") }

    @Test
    fun writesInAddOrderWithinTheBudget() {
        val (bulk, blocks) = line(25)
        server.scheduler.performOneTick()
        assertEquals(10, blocks.count { it.type == Material.AIR })
        assertTrue(blocks.take(10).all { it.type == Material.AIR }, "the first ten queued go first")
        server.scheduler.performTicks(2)
        assertTrue(blocks.all { it.type == Material.AIR })
        assertEquals(BulkChange.State.WRITTEN, bulk.state)
        assertEquals(25, bulk.held)
        assertTrue(blocks.all { manager.isTracked(it) })
    }

    @Test
    fun budgetIsSharedBetweenBulkChanges() {
        val a = (0 until 20).map { world.getBlockAt(it, 60, 0) }
        val b = (0 until 20).map { world.getBlockAt(it, 60, 5) }
        for (row in listOf(a, b)) {
            val bulk = manager.bulk("crater", world, AlteredBlockCauses.EXPLOSION).target { Material.AIR.createBlockData() }
            for (x in row) bulk.add(x.x, x.y, x.z)
            bulk.start()
        }
        server.scheduler.performOneTick()
        assertEquals(10, a.count { it.type == Material.AIR } + b.count { it.type == Material.AIR })
    }

    @Test
    fun restoresRimToCentreAfterTheLastWrite() {
        val (bulk, blocks) = line(20, restoreAfter = 5)
        server.scheduler.performTicks(2)   // written by the end of tick 2
        assertEquals(BulkChange.State.WRITTEN, bulk.state)
        server.scheduler.performTicks(4)
        assertTrue(blocks.all { it.type == Material.AIR }, "the timer counts from the last write")
        server.scheduler.performTicks(2)   // timer fires, first restore tick
        assertEquals(BulkChange.State.RESTORING, bulk.state)
        assertTrue(blocks.takeLast(10).all { it.type == Material.STONE }, "the rim comes back first")
        assertTrue(blocks.take(10).all { it.type == Material.AIR })
        server.scheduler.performTicks(2)
        assertTrue(blocks.all { it.type == Material.STONE })
        assertEquals(BulkChange.State.DONE, bulk.state)
        assertFalse(blocks.any { manager.isTracked(it) })
        assertTrue(bulkRecords(blocks[0]).isEmpty())
    }

    @Test
    fun targetDecidesAndSkipsBlockEntities() {
        world.getBlockAt(1, 60, 0).type = Material.CHEST
        world.getBlockAt(2, 60, 0).type = Material.DIRT
        val bulk = manager.bulk("crater", world, AlteredBlockCauses.EXPLOSION)
            .target { b -> if (b.type == Material.DIRT) null else Material.AIR.createBlockData() }
        for (x in 0..3) bulk.add(x, 60, 0)
        bulk.start()
        server.scheduler.performOneTick()
        assertEquals(Material.AIR, world.getBlockAt(0, 60, 0).type)
        assertEquals(Material.CHEST, world.getBlockAt(1, 60, 0).type)
        assertEquals(Material.DIRT, world.getBlockAt(2, 60, 0).type)
        assertEquals(2, bulk.held)
    }

    @Test
    fun repairToolsSeeAndMendBulkBlocks() {
        val (bulk, blocks) = line(10)
        server.scheduler.performOneTick()
        val found = manager.getBlocksInBoxByCause(world, 0, 50, -1, 20, 70, 1, AlteredBlockCauses.DAMAGE)
        assertEquals(10, found.size)
        assertEquals(Material.STONE, manager.previewUndoByCause(blocks[3], AlteredBlockCauses.DAMAGE)?.material)
        assertTrue(manager.undoByCause(blocks[3], AlteredBlockCauses.DAMAGE, true))
        assertEquals(Material.STONE, blocks[3].type)
        assertFalse(manager.isTracked(blocks[3]))
        assertEquals(9, bulk.held)
        // the wrench's other path: the Change view's undo
        manager.getByBlock(blocks[4]).single().undo(false)
        assertEquals(Material.STONE, blocks[4].type)
        assertEquals(8, bulk.held)
        assertNull(manager.previewUndoByCause(blocks[3], AlteredBlockCauses.DAMAGE))
    }

    @Test
    fun mendingEverythingEndsItEarly() {
        val (bulk, blocks) = line(5, restoreAfter = 1000)
        server.scheduler.performOneTick()
        for (b in blocks) manager.undoByCause(b, AlteredBlockCauses.DAMAGE, false)
        assertEquals(BulkChange.State.DONE, bulk.state)
        server.scheduler.performOneTick()
        assertTrue(bulkRecords(blocks[0]).isEmpty())
    }

    @Test
    fun restoreLeavesBlocksSomeoneReplaced() {
        val (bulk, blocks) = line(5)
        server.scheduler.performOneTick()
        blocks[2].type = Material.OAK_PLANKS   // a player built in the crater
        bulk.undo()
        server.scheduler.performOneTick()
        assertEquals(Material.OAK_PLANKS, blocks[2].type)
        assertTrue(blocks.filterIndexed { i, _ -> i != 2 }.all { it.type == Material.STONE })
        assertEquals(BulkChange.State.DONE, bulk.state)
    }

    @Test
    fun aPerBlockChangeOnABulkBlockStacksAndUnwinds() {
        val (bulk, blocks) = line(3)
        server.scheduler.performOneTick()
        val web = manager.apply("web", blocks[1], Material.COBWEB, false, AlteredBlockCauses.DAMAGE)
        assertEquals(Material.COBWEB, blocks[1].type)
        assertEquals(2, bulk.held, "the block left the bulk change")
        assertEquals(2, manager.getByBlock(blocks[1]).size)
        web.undo(false)
        assertEquals(Material.AIR, blocks[1].type)
        bulk.undo()
        server.scheduler.performOneTick()
        assertTrue(blocks.all { it.type == Material.STONE })
        assertFalse(manager.isTracked(blocks[1]))
    }

    @Test
    fun aBlockAlreadyChangedIsStackedNotOverwritten() {
        val block = world.getBlockAt(0, 60, 0)
        val dirt = manager.apply("dig", block, Material.DIRT, false, AlteredBlockCauses.DAMAGE)
        val bulk = manager.bulk("crater", world, AlteredBlockCauses.EXPLOSION).target { Material.AIR.createBlockData() }
        bulk.add(0, 60, 0).start()
        server.scheduler.performOneTick()
        assertEquals(Material.AIR, block.type)
        assertEquals(2, manager.getByBlock(block).size)
        bulk.undo()
        server.scheduler.performOneTick()
        assertEquals(Material.DIRT, block.type)
        dirt.undo(false)
        assertEquals(Material.STONE, block.type)
    }

    @Test
    fun oneRecordPerChunkAndARecordLeftByACrashRestoresOnLoad() {
        val (bulk, blocks) = line(5)
        server.scheduler.performOneTick()
        assertEquals(1, bulkRecords(blocks[0]).size)
        // a record whose bulk change no longer lives (the server crashed): restore on chunk load
        val pdc = blocks[0].chunk.persistentDataContainer
        val key = bulkRecords(blocks[0]).single()   // one segment: all five written in one tick
        val saved = pdc.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER)!!
        bulk.undo()
        server.scheduler.performOneTick()
        for (b in blocks) b.type = Material.AIR
        blocks[1].type = Material.OAK_PLANKS
        pdc.set(org.bukkit.NamespacedKey(plugin, "altered_bulk_00000000-0000-0000-0000-000000000001_0"),
            org.bukkit.persistence.PersistentDataType.TAG_CONTAINER, saved)
        server.pluginManager.callEvent(ChunkLoadEvent(blocks[0].chunk, false))
        assertEquals(Material.OAK_PLANKS, blocks[1].type, "a block that isn't what was written stays")
        assertTrue(blocks.filterIndexed { i, _ -> i != 1 }.all { it.type == Material.STONE })
        assertTrue(bulkRecords(blocks[0]).isEmpty())
    }

    @Test
    fun recordsGrowBySegmentsAndGoWhenTheChunkIsEmpty() {
        val (bulk, blocks) = line(15)   // budget 10: two ticks, two segments in one chunk
        server.scheduler.performTicks(2)
        assertEquals(2, bulkRecords(blocks[0]).size)
        manager.undoByCause(blocks[0], AlteredBlockCauses.DAMAGE, false)
        server.scheduler.performOneTick()
        assertEquals(2, bulkRecords(blocks[0]).size, "a mend doesn't rewrite them")
        bulk.undo()
        server.scheduler.performTicks(2)
        assertTrue(bulkRecords(blocks[0]).isEmpty())
    }

    @Test
    fun undoAllByCauseRestoresBulkChangesToo() {
        val (bulk, blocks) = line(5)
        server.scheduler.performOneTick()
        manager.undoAllByCause(AlteredBlockCauses.DAMAGE, false)
        server.scheduler.performOneTick()
        assertTrue(blocks.all { it.type == Material.STONE })
        assertEquals(BulkChange.State.DONE, bulk.state)
    }

    @Test
    fun restoreAllPutsLoadedBulkBlocksBack() {
        val (_, blocks) = line(5)
        server.scheduler.performOneTick()
        manager.restoreAll()
        assertTrue(blocks.all { it.type == Material.STONE })
    }

    @Test
    fun packingRoundTrips() {
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-1, -64, -1), Triple(29_999_999, 319, -29_999_999), Triple(-5, 2047, 17))) {
            val p = BulkChange.pack(x, y, z)
            assertEquals(x, BulkChange.unpackX(p))
            assertEquals(y, BulkChange.unpackY(p))
            assertEquals(z, BulkChange.unpackZ(p))
        }
    }
}
