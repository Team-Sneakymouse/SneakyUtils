package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * Inventory contents survive every restore path. A placed state's getInventory() reads the live
 * block, which after the change is no longer the container; only the snapshot inventory holds what
 * was captured.
 */
class AlteredBlockContainerTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyUtils
    private lateinit var world: WorldMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, 64)
        server.addWorld(world)
        for (cx in -1..1) for (cz in -1..1) world.getChunkAt(cx, cz)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun itemFor(material: Material): ItemStack = when (material) {
        Material.LECTERN -> ItemStack(Material.WRITABLE_BOOK)
        Material.DECORATED_POT -> ItemStack(Material.DIAMOND)
        else -> ItemStack(Material.DIAMOND, 5)
    }

    private fun fill(block: Block, material: Material): ItemStack {
        block.type = material
        val state = block.state as TileStateInventoryHolder
        val stack = itemFor(material)
        state.snapshotInventory.setItem(0, stack)
        state.update(true, false)
        assertEquals(stack, contents(block))
        return stack
    }

    private fun contents(block: Block): ItemStack? = (block.state as TileStateInventoryHolder).snapshotInventory.getItem(0)

    private val containers = listOf(Material.CHEST, Material.BARREL, Material.SHULKER_BOX, Material.HOPPER)

    @ParameterizedTest
    @ValueSource(strings = ["CHEST", "BARREL", "SHULKER_BOX", "HOPPER"])
    fun manualUndoKeepsContents(name: String) {
        val material = Material.valueOf(name)
        val block = world.getBlockAt(1, 60, 1)
        val stack = fill(block, material)
        val change = SneakyUtils.getAlteredBlockManager().apply("c", block, Material.AIR, false)
        change.undo(false)
        assertEquals(material, block.type)
        assertEquals(stack, contents(block))
    }

    @Test
    fun timedRestoreKeepsContents() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val stacks = containers.mapIndexed { i, material ->
            val block = world.getBlockAt(i, 60, 3)
            fill(block, material).also { manager.apply("t", block, Material.AIR, false, 2L) }
        }
        server.scheduler.performTicks(2)
        for ((i, material) in containers.withIndex()) {
            val block = world.getBlockAt(i, 60, 3)
            assertEquals(material, block.type)
            assertEquals(stacks[i], contents(block), "$material")
        }
    }

    @Test
    fun markerCrashRecoveryKeepsContents() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val blocks = containers.mapIndexed { i, material -> world.getBlockAt(i, 60, 5) to material }
        val stacks = blocks.map { (block, material) -> fill(block, material) }
        for ((block, _) in blocks) manager.apply("c", block, Material.AIR, false)
        assertTrue(blocks.all { it.first.type == Material.AIR })

        val restarted = AlteredBlockManager(plugin)
        for (marker in world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }) {
            restarted.restoreFromMarker(marker)
        }
        for ((index, pair) in blocks.withIndex()) {
            assertEquals(pair.second, pair.first.type)
            assertEquals(stacks[index], contents(pair.first), "${pair.second}")
        }
    }

    // Chiseled bookshelves and jukeboxes take the same path but can't be checked here: MockBukkit's
    // bookshelf mock hands out a throwaway snapshot inventory and its jukebox is unimplemented.
    @ParameterizedTest
    @ValueSource(strings = ["CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "FURNACE", "LECTERN", "DECORATED_POT"])
    fun serializedTileDataIsTakenFromTheSnapshot(name: String) {
        val material = Material.valueOf(name)
        val block = world.getBlockAt(1, 60, 7)
        val stack = fill(block, material)
        val captured = block.state
        block.type = Material.AIR
        block.type = material // the live block is now a different, empty one
        // On Paper a placed state's getInventory() reads the live block, which no longer holds the
        // items; MockBukkit keeps the old live inventory object, so empty it to model that.
        (captured as TileStateInventoryHolder).inventory.clear()
        val json = AlteredBlockManager.serializeTileData(captured)!!
        block.type = Material.AIR
        block.type = material
        AlteredBlockManager.applySerializedTileData(block, json, false)
        assertEquals(stack, contents(block))
    }

    @Test
    fun containerRecordsWrittenBeforeAllHoldersWereSavedStillRestore() {
        // The shape older builds wrote for a Container: kind "container" and Base64 item bytes.
        val block = world.getBlockAt(2, 60, 7)
        val stack = fill(block, Material.CHEST)
        val json = AlteredBlockManager.serializeTileData(block.state)!!
        assertTrue(json.contains("\"kind\":\"container\""))
        block.type = Material.AIR
        block.type = Material.CHEST
        AlteredBlockManager.applySerializedTileData(block, json, false)
        assertEquals(stack, contents(block))
    }

    @ParameterizedTest
    @ValueSource(strings = ["CHEST", "BARREL", "HOPPER", "FURNACE", "LECTERN", "DECORATED_POT"])
    fun replacingEmptiesTheLiveInventorySoNothingDrops(name: String) {
        val material = Material.valueOf(name)
        val block = world.getBlockAt(1, 60, 9)
        val stack = fill(block, material)
        // MockBukkit hands every state of a block the same live inventory object.
        val live = (block.state as TileStateInventoryHolder).inventory
        val change = SneakyUtils.getAlteredBlockManager().apply("c", block, Material.AIR, false)
        assertTrue(live.isEmpty, "live inventory still holds items Paper would drop")
        assertEquals(stack, (change.fromState as TileStateInventoryHolder).snapshotInventory.getItem(0))
        change.undo(false)
        assertEquals(material, block.type)
        assertEquals(stack, contents(block)) // back exactly once
    }

    @Test
    fun changingTheSameBlockTypeKeepsItsItems() {
        val block = world.getBlockAt(1, 60, 11)
        val stack = fill(block, Material.CHEST)
        val live = (block.state as TileStateInventoryHolder).inventory
        val change = SneakyUtils.getAlteredBlockManager()
            .apply("c", block, Material.CHEST.createBlockData("[facing=east]"), false)
        assertEquals(stack, live.getItem(0))
        change.undo(false)
        assertEquals(stack, contents(block))
    }

    // MockBukkit doesn't join double chests (each half has its own live inventory), so this is
    // regression coverage only: on Paper the live inventory spans both halves, which is why only the
    // replaced block's own inventory (Chest.getBlockInventory) is emptied.
    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun replacingOneHalfOfADoubleChestLeavesTheOtherHalfAlone(replaced: Int) {
        val halves = listOf(world.getBlockAt(1, 60, 13), world.getBlockAt(2, 60, 13))
        for (half in halves) half.type = Material.CHEST // MockBukkit makes the chest state on a type change
        halves[0].blockData = Material.CHEST.createBlockData("[facing=north,type=left]")
        halves[1].blockData = Material.CHEST.createBlockData("[facing=north,type=right]")
        val stacks = listOf(ItemStack(Material.DIAMOND, 5), ItemStack(Material.EMERALD, 7))
        for ((block, stack) in halves.zip(stacks)) {
            val state = block.state as org.bukkit.block.Chest
            state.snapshotInventory.setItem(0, stack)
            state.update(true, false)
        }
        val kept = 1 - replaced
        val change = SneakyUtils.getAlteredBlockManager().apply("c", halves[replaced], Material.AIR, false)
        assertEquals(stacks[kept], contents(halves[kept]))
        change.undo(false)
        assertEquals(Material.CHEST, halves[replaced].type)
        assertEquals(stacks[replaced], contents(halves[replaced]))
        assertEquals(stacks[kept], contents(halves[kept]))
    }

    // ---- Same-type changes leave the live inventory alone ----------------------------------------

    private val east get() = Material.CHEST.createBlockData("[facing=east]")

    /** Takes all but two diamonds out of [block]'s live inventory, as a player would. */
    private fun withdraw(block: Block): ItemStack {
        val left = ItemStack(Material.DIAMOND, 2)
        // Through an updated copy: MockBukkit's live inventory doesn't feed the block's next state,
        // and its getState() hands out the block's own state object, which a change may have captured.
        val state = block.getState(true) as TileStateInventoryHolder
        state.snapshotInventory.setItem(0, left)
        state.update(true, false)
        assertEquals(left, contents(block))
        return left
    }

    @Test
    fun sameTypeUndoDoesNotGiveBackWithdrawnItems() {
        val block = world.getBlockAt(1, 60, 15)
        fill(block, Material.CHEST)
        val change = SneakyUtils.getAlteredBlockManager().apply("c", block, east, false)
        val left = withdraw(block)
        change.undo(false)
        assertEquals(Material.CHEST, block.type)
        assertEquals(left, contents(block))
    }

    @Test
    fun sameTypeTimedRestoreDoesNotGiveBackWithdrawnItems() {
        val block = world.getBlockAt(1, 60, 15)
        fill(block, Material.CHEST)
        SneakyUtils.getAlteredBlockManager().apply("c", block, east, false, 2L)
        val left = withdraw(block)
        server.scheduler.performTicks(2)
        assertEquals(Material.CHEST, block.type)
        assertEquals(left, contents(block))
    }

    @Test
    fun sameTypeCrashRecoveryDoesNotGiveBackWithdrawnItems() {
        val block = world.getBlockAt(1, 60, 15)
        fill(block, Material.CHEST)
        SneakyUtils.getAlteredBlockManager().apply("c", block, east, false)
        val left = withdraw(block)
        val restarted = AlteredBlockManager(plugin)
        for (marker in world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }) {
            restarted.restoreFromMarker(marker)
        }
        assertEquals(Material.CHEST, block.type)
        assertEquals(left, contents(block))
    }

    /**
     * Rotates a filled chest, takes items out, then replaces it with air. The replace is not the
     * block's first change, so the manager leaves the items in the live inventory for Paper to drop
     * (MockBukkit doesn't drop them; the test checks the manager did not take them) and never writes
     * them, or the five first held, back.
     */
    private fun rotateThenReplace(block: Block): Pair<AlteredBlockManager.Change, AlteredBlockManager.Change> {
        val manager = SneakyUtils.getAlteredBlockManager()
        fill(block, Material.CHEST)
        val rotate = manager.apply("c", block, east, false)
        val left = withdraw(block)
        val live = (block.state as TileStateInventoryHolder).inventory
        val replace = manager.apply("c", block, Material.AIR, false)
        assertEquals(left, live.getItem(0), "the manager took the items Paper would drop")
        // Paper drops them as the block entity goes. MockBukkit keeps the old chest state through
        // setBlockData (its getState() is that very object), so empty it to model the drop.
        (block.state as TileStateInventoryHolder).snapshotInventory.clear()
        return rotate to replace
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun replacingARotatedChestLeavesItsItemsToPaper(olderFirst: Boolean) {
        val block = world.getBlockAt(1, 60, 17)
        val (rotate, replace) = rotateThenReplace(block)
        if (olderFirst) {
            rotate.undo(false) // spliced out: the block stays air
            assertEquals(Material.AIR, block.type)
            replace.undo(false)
        } else {
            replace.undo(false)
            assertEquals(Material.CHEST, block.type)
            assertEquals(null, contents(block))
            rotate.undo(false)
        }
        assertEquals(Material.CHEST, block.type)
        assertEquals(null, contents(block)) // nothing written back: they dropped
        assertTrue(droppedItems().isEmpty())
    }

    @Test
    fun crashAfterReplacingARotatedChestRestoresItEmpty() {
        val block = world.getBlockAt(1, 60, 17)
        rotateThenReplace(block)
        val restarted = AlteredBlockManager(plugin)
        for (marker in world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }) {
            restarted.restoreFromMarker(marker)
        }
        assertEquals(Material.CHEST, block.type)
        assertEquals(null, contents(block)) // the items dropped at the replace; none come back twice
    }

    private fun droppedItems(): List<ItemStack> =
        world.entities.filterIsInstance<org.bukkit.entity.Item>().map { it.itemStack }

    /**
     * A filled chest is replaced with stone (the first change takes its items), then a chest is
     * placed there again and players fill it. Restoring the original never overwrites what that
     * chest holds: the captured items drop at the block instead.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun restoringOntoAFilledChestDropsTheSavedItemsInstead(crash: Boolean) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 21)
        val saved = fill(block, Material.CHEST)
        val replace = manager.apply("c", block, Material.STONE, false)
        val chestAgain = manager.apply("c", block, Material.CHEST, false)
        val placed = ItemStack(Material.EMERALD, 3)
        val state = block.getState(true) as TileStateInventoryHolder
        state.snapshotInventory.setItem(0, placed)
        state.update(true, false)
        if (crash) {
            val restarted = AlteredBlockManager(plugin)
            for (marker in world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }) {
                restarted.restoreFromMarker(marker)
            }
        } else {
            replace.undo(false) // spliced out
            chestAgain.undo(false)
        }
        assertEquals(Material.CHEST, block.type)
        assertEquals(placed, contents(block))
        assertEquals(listOf(saved), droppedItems())
    }

    @Test
    fun aChangeOnTopOfAReplacedChestDoesNotLoseItsItems() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 19)
        val stack = fill(block, Material.CHEST)
        val replace = manager.apply("c", block, Material.AIR, false)
        val onTop = manager.apply("c", block, Material.STONE, false)
        replace.undo(false) // spliced out
        assertEquals(Material.STONE, block.type)
        onTop.undo(false)
        assertEquals(Material.CHEST, block.type)
        assertEquals(stack, contents(block))
    }

    // ---- Replays and retries never put items back twice ------------------------------------------

    private fun markers(): List<org.bukkit.entity.Entity> =
        world.entities.filter { it.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG) }

    /** Unloads [marker] (the old object goes invalid) and returns a function that loads a copy of it. */
    private fun unload(marker: org.bukkit.entity.Entity): () -> org.bukkit.entity.Entity {
        val pdc = marker.persistentDataContainer
        val location = marker.location
        val tags = marker.scoreboardTags.toSet()
        marker.remove()
        return {
            val copy = org.mockbukkit.mockbukkit.entity.MarkerMock(server, marker.uniqueId)
            copy.setLocation(location)
            tags.forEach { copy.addScoreboardTag(it) }
            pdc.copyTo(copy.persistentDataContainer, true)
            server.registerEntity(copy)
            copy
        }
    }

    @Test
    fun aMarkerStillThereAfterARestartDoesNotReturnItemsTheUndoAlreadyPutBack() {
        val block = world.getBlockAt(1, 60, 25)
        fill(block, Material.CHEST)
        val change = SneakyUtils.getAlteredBlockManager().apply("c", block, Material.AIR, false)
        val load = unload(markers().single())
        change.undo(false) // the marker is not loaded: it can't be removed now
        assertEquals(Material.CHEST, block.type)
        val left = withdraw(block)

        // A restart before its entities loaded: the marker is still saved in the chunk.
        val marker = load()
        AlteredBlockManager(plugin).restoreFromMarker(marker)
        assertEquals(left, contents(block))
        assertTrue(droppedItems().isEmpty(), "dropped a second copy")
        assertTrue(!marker.isValid)
    }

    // ---- register/add: an external edit keeps custody of the items ------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["undo", "timed", "crash"])
    fun aRegisteredReplacementNeverPutsTheBrokenChestsItemsBack(path: String) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 39)
        val stack = fill(block, Material.CHEST)
        val fromData = block.blockData
        val fromState = block.getState(true) // a snapshot, as Block.getState() is on Paper
        // The external edit breaks the chest normally: its items drop and players pick them up.
        world.dropItemNaturally(block.location, stack.clone())
        block.type = Material.AIR
        // It puts a barrel there. MockBukkit keeps that block state through setBlockData, so it stands
        // in for the fresh block entity a restored chest gets on Paper: written items would land here.
        block.type = Material.BARREL
        val change = if (path == "timed") manager.register("c", block, fromData, fromState, false, 2L)
        else manager.register("c", block, fromData, fromState)
        val marker = markers().single()
        val json = marker.persistentDataContainer.get(
            org.bukkit.NamespacedKey(plugin, "altered_block_state"), org.bukkit.persistence.PersistentDataType.STRING)
        assertTrue(json == null || !json.contains("\"items\""), "the marker holds the items: $json")

        when (path) {
            "undo" -> change.undo(false)
            "timed" -> server.scheduler.performTicks(2)
            else -> AlteredBlockManager(plugin).restoreFromMarker(marker)
        }
        assertEquals(Material.CHEST, block.type)
        val held = (block.state as TileStateInventoryHolder).snapshotInventory.contents.filterNotNull()
        assertTrue(held.isEmpty(), "a second copy was written back: $held")
        assertEquals(listOf(stack), droppedItems()) // the one copy the break dropped
    }

    // ---- apply: custody follows what apply actually did -----------------------------------------

    @Test
    fun aChestBrokenByAPhysicsListenerDuringARotationIsNotHeldByTheChange() {
        // Rotating keeps the chest, so apply takes nothing. A synchronous BlockPhysicsEvent listener
        // breaks it during the write (its items drop) and puts a barrel there; MockBukkit fires no
        // physics, so a spy runs that listener inside setBlockData.
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = org.mockito.Mockito.spy(world.getBlockAt(1, 60, 41))
        val stack = fill(block, Material.CHEST)
        val rejected = ArrayList<Throwable>()
        org.mockito.Mockito.doAnswer { call ->
            call.callRealMethod()
            world.dropItemNaturally(block.location, stack.clone())
            block.type = Material.BARREL // MockBukkit keeps this state: written items would land here
            try {
                manager.apply("x", block, Material.GLASS, false)
            } catch (e: IllegalStateException) {
                rejected.add(e) // the block is mid-apply: other edits wait
            }
            null
        }.`when`(block).setBlockData(org.mockito.Mockito.any(org.bukkit.block.data.BlockData::class.java), org.mockito.Mockito.eq(true))

        val change = manager.apply("c", block, east, true)
        assertEquals(1, rejected.size)
        assertEquals(listOf(change), manager.getByBlock(block))
        val json = markers().single().persistentDataContainer.get(
            org.bukkit.NamespacedKey(plugin, "altered_block_state"), org.bukkit.persistence.PersistentDataType.STRING)
        assertTrue(json == null || !json.contains("\"items\""), "the marker holds the dropped items: $json")

        change.undo(false)
        assertEquals(Material.CHEST, block.type)
        assertTrue((block.state as TileStateInventoryHolder).snapshotInventory.contents.filterNotNull().isEmpty())
        assertEquals(listOf(stack), droppedItems()) // only the copy the break dropped
    }

    @Test
    fun aRefusedRecoveryMarkerLeavesTheItemsToPaper() {
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun on(event: org.bukkit.event.entity.EntitySpawnEvent) {
                if (event.entity is org.bukkit.entity.Marker) event.isCancelled = true
            }
        }, plugin)
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 43)
        val stack = fill(block, Material.CHEST)
        val live = (block.state as TileStateInventoryHolder).inventory

        val change = manager.apply("c", block, Material.AIR, false)
        assertEquals(Material.AIR, block.type)
        assertEquals(stack, live.getItem(0), "the manager took items it has no marker for")
        assertTrue(markers().isEmpty())
        assertEquals(listOf(change), manager.getByBlock(block)) // still registered, as on origin/main

        // Paper drops them as the block entity goes; model that, then undo writes no copy back.
        (block.state as TileStateInventoryHolder).snapshotInventory.clear()
        change.undo(false)
        assertEquals(Material.CHEST, block.type)
        assertEquals(null, contents(block))
        assertTrue(!manager.isTracked(block))
    }

    /** Runs [onSpawn] from inside every ItemSpawnEvent, as another plugin's listener would. */
    private fun onItemSpawn(onSpawn: () -> Unit) {
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun on(@Suppress("UNUSED_PARAMETER") event: org.bukkit.event.entity.ItemSpawnEvent) = onSpawn()
        }, plugin)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun aListenerCallingBackDuringARestoreCantCorruptTheBlock(crash: Boolean) {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 37)
        val saved = fill(block, Material.CHEST)
        val replace = manager.apply("c", block, Material.STONE, false)
        val chestAgain = manager.apply("c", block, Material.CHEST, false)
        val placed = ItemStack(Material.EMERALD, 3)
        val state = block.getState(true) as TileStateInventoryHolder
        state.snapshotInventory.setItem(0, placed)
        state.update(true, false)
        val marker = markers().single()
        val restarted = AlteredBlockManager(plugin)

        // The saved diamonds drop beside the filled chest; the listener reenters on that spawn.
        val rejected = ArrayList<Throwable>()
        onItemSpawn {
            if (crash) restarted.restoreFromMarker(marker) else chestAgain.undo(false)
            try {
                (if (crash) restarted else manager).apply("x", block, Material.GLASS, false)
            } catch (e: IllegalStateException) {
                rejected.add(e)
            }
        }
        if (crash) {
            restarted.restoreFromMarker(marker)
        } else {
            replace.undo(false) // spliced out
            chestAgain.undo(false)
            assertTrue(!manager.isTracked(block))
        }
        assertEquals(Material.CHEST, block.type)
        assertEquals(placed, contents(block))
        assertEquals(listOf(saved), droppedItems()) // dropped once
        assertEquals(1, rejected.size) // the new change on the block was refused, not half-applied
        assertTrue(markers().isEmpty())
    }

    @Test
    fun aFailedFinalRestoreKeepsTheChangeAndCanBeRetried() {
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = org.mockito.Mockito.spy(world.getBlockAt(1, 60, 27))
        val stack = fill(block, Material.CHEST)
        val change = manager.apply("c", block, Material.AIR, false)
        org.mockito.Mockito.doThrow(IllegalStateException("write failed")).doCallRealMethod()
            .`when`(block).setBlockData(org.mockito.Mockito.any(org.bukkit.block.data.BlockData::class.java), org.mockito.Mockito.anyBoolean())

        org.junit.jupiter.api.assertThrows<IllegalStateException> { change.undo(false) }
        assertTrue(manager.isTracked(block))
        assertEquals(listOf(change), manager.getByBlock(block))
        assertEquals(1, markers().size)

        change.undo(false)
        assertEquals(Material.CHEST, block.type)
        assertEquals(stack, contents(block))
        assertTrue(!manager.isTracked(block))
        assertTrue(markers().isEmpty())
        assertTrue(droppedItems().isEmpty())
    }

    @Test
    fun aReplayedMarkerRestoreDoesNotPutItsItemsBackTwice() {
        // Crash recovery that got as far as writing the items, then ran again (an exception after
        // the write, or the marker loading twice): the second run changes nothing.
        val block = world.getBlockAt(1, 60, 29)
        val stack = fill(block, Material.CHEST)
        SneakyUtils.getAlteredBlockManager().apply("c", block, Material.AIR, false)
        val load = unload(markers().single())
        val first = load()
        AlteredBlockManager(plugin).restoreFromMarker(first)
        assertEquals(stack, contents(block))
        val left = withdraw(block)
        AlteredBlockManager(plugin).restoreFromMarker(load())
        assertEquals(left, contents(block))
        assertTrue(droppedItems().isEmpty())
    }

    @Test
    fun aRestoreWithoutItemsNeverReadsTheInventory() {
        val chest = org.mockito.Mockito.mock(org.bukkit.block.Chest::class.java)
        assertEquals(null, AlteredBlockManager.serializeTileDataWithoutItems(chest))
        org.mockito.Mockito.verify(chest, org.mockito.Mockito.never()).snapshotInventory

        val lectern = org.mockito.Mockito.mock(org.bukkit.block.Lectern::class.java)
        org.mockito.Mockito.`when`(lectern.page).thenReturn(3)
        val json = com.google.gson.JsonParser.parseString(AlteredBlockManager.serializeTileDataWithoutItems(lectern)).asJsonObject
        assertEquals("lectern", json.get("kind").asString)
        assertEquals(3, json.get("page").asInt)
        assertTrue(!json.has("items"))
        org.mockito.Mockito.verify(lectern, org.mockito.Mockito.never()).snapshotInventory
    }

    // ---- Jukeboxes: left as before this build ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun aJukeboxRecordIsNeitherSavedNorTakenNorWrittenBack(crash: Boolean) {
        // Writing a record back updates neighbours on Paper whatever the physics flag, so the record
        // is left to Paper (dropped on replace) as origin/main did. MockBukkit's jukebox has no
        // inventory: touching it (emptying or saving the record) would throw here.
        val manager = SneakyUtils.getAlteredBlockManager()
        val block = world.getBlockAt(1, 60, 31)
        block.type = Material.JUKEBOX
        val state = block.state as org.bukkit.block.Jukebox
        state.setRecord(ItemStack(Material.MUSIC_DISC_CAT))
        state.update(true, false)
        assertEquals(null, AlteredBlockManager.serializeTileData(block.state))

        val change = manager.apply("c", block, Material.AIR, false)
        assertEquals(Material.MUSIC_DISC_CAT, (block.state as org.bukkit.block.Jukebox).record.type) // not taken
        if (crash) {
            val marker = markers().single()
            val stateKey = org.bukkit.NamespacedKey(plugin, "altered_block_state")
            assertTrue(!marker.persistentDataContainer.has(stateKey), "the marker saved jukebox tile data")
            // MockBukkit can't parse "minecraft:jukebox[has_record=false]", so the block type isn't
            // checked here; recovery must still run without touching the record and remove the marker.
            AlteredBlockManager(plugin).restoreFromMarker(marker)
        } else {
            change.undo(false)
            assertEquals(Material.JUKEBOX, block.type)
        }
        assertTrue(markers().isEmpty())
    }

    // ---- Markers written by older builds -------------------------------------------------------

    /**
     * A marker exactly as origin/main's spawnMarker writes it: no format key, and the container's
     * items taken from `state.inventory.contents` as a `{"v":1,"kind":"container","items":...}` record.
     */
    private fun originMainMarker(block: Block, items: Array<ItemStack?>): org.bukkit.entity.Entity {
        val marker = world.spawnEntity(block.location.add(0.5, 0.5, 0.5), org.bukkit.entity.EntityType.MARKER)
        marker.addScoreboardTag(SneakyUtils.ALTERED_BLOCK_TAG)
        val json = com.google.gson.JsonObject()
        json.addProperty("v", 1)
        json.addProperty("kind", "container")
        json.addProperty("items", java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)))
        val pdc = marker.persistentDataContainer
        fun key(name: String) = org.bukkit.NamespacedKey(plugin, name)
        val string = org.bukkit.persistence.PersistentDataType.STRING
        val int = org.bukkit.persistence.PersistentDataType.INTEGER
        pdc.set(key("altered_block_data"), string, block.blockData.asString)
        pdc.set(key("altered_block_state"), string, json.toString())
        pdc.set(key("altered_block_world"), string, world.uid.toString())
        pdc.set(key("altered_block_x"), int, block.x)
        pdc.set(key("altered_block_y"), int, block.y)
        pdc.set(key("altered_block_z"), int, block.z)
        return marker
    }

    @Test
    fun anOlderBuildsContainerRecordTakesOriginMainsExactCallPath() {
        val items = arrayOfNulls<ItemStack>(27).also { it[0] = ItemStack(Material.DIAMOND, 5) }
        val json = com.google.gson.JsonObject().apply {
            addProperty("v", 1)
            addProperty("kind", "container")
            addProperty("items", java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)))
        }.toString()
        val block = org.mockito.Mockito.mock(Block::class.java)
        val state = org.mockito.Mockito.mock(org.bukkit.block.Chest::class.java)
        val live = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory::class.java)
        org.mockito.Mockito.`when`(block.state).thenReturn(state)
        org.mockito.Mockito.`when`(state.inventory).thenReturn(live)

        AlteredBlockManager.applyLegacyTileData(block, json, false)

        val order = org.mockito.Mockito.inOrder(live, state)
        order.verify(live).setContents(org.mockito.AdditionalMatchers.aryEq(ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(items))))
        order.verify(state).update(true, false)
        org.mockito.Mockito.verify(state, org.mockito.Mockito.never()).snapshotInventory
    }

    /** origin/main's restoreFromMarker + applySerializedTileData container branch, verbatim. */
    private fun originMainRecover(block: Block, data: String, json: String) {
        block.setBlockData(org.bukkit.Bukkit.createBlockData(data), false)
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val state = block.state
        if (state !is org.bukkit.block.Container) return
        @Suppress("UNCHECKED_CAST")
        state.inventory.contents = ItemStack.deserializeItemsFromBytes(
            java.util.Base64.getDecoder().decode(root.get("items").asString)) as Array<ItemStack?>
        state.update(true, false)
    }

    @ParameterizedTest
    @ValueSource(strings = ["withdraw", "deposit"])
    fun anOlderBuildsMarkerRecoversAsOriginMainAfterPlayersUsedTheChest(use: String) {
        // origin/main rotated a chest holding five diamonds, then players used it before recovery.
        val ours = world.getBlockAt(1, 60, 35)
        val theirs = world.getBlockAt(3, 60, 35)
        val payload = arrayOfNulls<ItemStack>(27).also { it[0] = ItemStack(Material.DIAMOND, 5) }
        for (block in listOf(ours, theirs)) {
            fill(block, Material.CHEST)
            val state = block.getState(true) as TileStateInventoryHolder
            if (use == "withdraw") state.snapshotInventory.setItem(0, ItemStack(Material.DIAMOND, 2))
            else state.snapshotInventory.setItem(1, ItemStack(Material.EMERALD, 4))
            state.update(true, false)
        }
        val marker = originMainMarker(ours, payload)
        val pdc = marker.persistentDataContainer
        val data = pdc.get(org.bukkit.NamespacedKey(plugin, "altered_block_data"), org.bukkit.persistence.PersistentDataType.STRING)!!
        val json = pdc.get(org.bukkit.NamespacedKey(plugin, "altered_block_state"), org.bukkit.persistence.PersistentDataType.STRING)!!

        AlteredBlockManager(plugin).restoreFromMarker(marker)
        originMainRecover(theirs, data, json)

        val inv = { b: Block -> (b.state as TileStateInventoryHolder).snapshotInventory.contents.toList() }
        val live = { b: Block -> (b.state as TileStateInventoryHolder).inventory.contents.toList() }
        assertEquals(inv(theirs), inv(ours))
        assertEquals(live(theirs), live(ours))
        assertTrue(droppedItems().isEmpty())
        assertTrue(!marker.isValid)
    }

    @Test
    fun anOlderBuildsMarkerWritesItsPayloadWithoutDroppingCopies() {
        // origin/main rotated a chest holding five diamonds: its marker holds the five, and so does the chest.
        val block = world.getBlockAt(1, 60, 23)
        val stack = fill(block, Material.CHEST)
        val items = arrayOfNulls<ItemStack>(27).also { it[0] = stack.clone() }
        val marker = originMainMarker(block, items)
        AlteredBlockManager(plugin).restoreFromMarker(marker)
        assertEquals(stack, contents(block))
        assertTrue(droppedItems().isEmpty(), "dropped a second copy")
        assertTrue(!marker.isValid)
    }
}
