package com.sneakyutils.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.sneakyutils.SneakyUtils
import io.papermc.paper.block.TileStateInventoryHolder
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Banner
import org.bukkit.block.Chest
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Jukebox
import org.bukkit.block.Lectern
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Marker
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.IdentityHashMap
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger

/**
 * Shared reversible block changes. Call all APIs on the server thread.
 * Timed overloads require positive server-tick durations (not wall-clock time);
 * zero/negative values throw [IllegalArgumentException] before registration or mutation.
 * Automatic undo uses the supplied physics flag; explicit undo may override it and cancels the timer.
 * Register each Change instance only once while active, and undo that exact instance.
 *
 * Each block keeps every change made to it, oldest first, and one persistent marker holding the
 * block's original state (the crash fallback). Undoing the newest change writes the state it
 * replaced. Undoing an older change leaves the world alone and splices it out: the change above it
 * takes over its restore state, so later undos still end at the original.
 *
 * Changes may carry causes from the fixed set in [AlteredBlockCauses] (such as `"explosion"`), so
 * tools can find or undo changes by what caused them rather than by which feature made them.
 * Cause lookups follow its implications: looking up `"damage"` also finds `"explosion"` and `"fire"`.
 */
class AlteredBlockManager(private val plugin: JavaPlugin) {

    private val pendingUndos = IdentityHashMap<Change, BukkitTask>()

    /** Callbacks run once when their active change is undone or spliced out; see [onUndone]. */
    /** Changes that keep external edits (see [preserveExternalEdits]), with the data they wrote. */
    private val preservedData = IdentityHashMap<Change, BlockData>()
    private val undoListeners = IdentityHashMap<Change, MutableList<Runnable>>()

    /** Markers removed while their entities were not loaded (see [removeMarker]); dropped as they load. */
    private val removedMarkerIds: MutableSet<UUID> = LinkedHashSet()

    /** Most UUIDs [removedMarkerIds] keeps; the oldest go first, with a warning. */
    internal var removedMarkerCap = 4096

    /** Size of [removedMarkerIds]; for tests. */
    internal val removedMarkerCount: Int get() = removedMarkerIds.size

    /** How long [removeMarker] keeps looking for a marker that was not loaded, in ticks. */
    private val markerRetryTicks = 100L
    private val alteredBlocks: MutableMap<Block, AlteredBlock> = HashMap()

    /** Tracked blocks by world UID, then chunk key. Kept in step with [alteredBlocks] for [getBlocksInBox]. */
    private val blocksByChunk: MutableMap<UUID, MutableMap<Long, MutableSet<Block>>> = HashMap()

    /**
     * Tracked blocks by internal name: each block with at least one active change under that name.
     * Kept in step with [alteredBlocks] for [getByInternalName] and [undoAll].
     */
    private val blocksByName: MutableMap<String, MutableSet<Block>> = HashMap()

    /** Tracked blocks by cause: each block with at least one active change carrying that cause. */
    private val blocksByCause: MutableMap<String, MutableSet<Block>> = HashMap()
    private val logger: Logger = plugin.logger
    private val blockDataKey = NamespacedKey(plugin, "altered_block_data")
    private val blockStateKey = NamespacedKey(plugin, "altered_block_state")
    private val worldKey = NamespacedKey(plugin, "altered_block_world")
    private val xKey = NamespacedKey(plugin, "altered_block_x")
    private val yKey = NamespacedKey(plugin, "altered_block_y")
    private val zKey = NamespacedKey(plugin, "altered_block_z")

    /**
     * Set on markers that follow this build's inventory custody rules (only a block's first change
     * holds items, and only items it took out of the world). Markers without it come from older
     * builds and are recovered as those builds did.
     */
    private val formatKey = NamespacedKey(plugin, "altered_block_format")

    /**
     * Written into a block entity's own PDC, in the same state update that puts a marker's captured
     * items back: the marker's UUID. A replay of that marker (a retry after an exception, or a marker
     * that loads after a restart although its change was already undone) sees the items are back.
     */
    private val restoredKey = NamespacedKey(plugin, "altered_block_restored")

    /**
     * Blocks being changed by [apply] (until registered) or restored (an undo, or recovery from a
     * marker). These call other code synchronously (EntitySpawnEvent, BlockPhysicsEvent, a displaced
     * item's ItemSpawnEvent), which must not change the same block meanwhile: an undo there is
     * ignored, a new change throws.
     */
    private val restoring: MutableSet<Block> = HashSet()

    private inline fun <T> whileRestoring(block: Block, action: () -> T): T {
        restoring.add(block)
        try {
            return action()
        } finally {
            restoring.remove(block)
        }
    }

    private fun checkNotRestoring(block: Block) {
        check(block !in restoring) { "Altered block at ${block.location} is being changed or restored; change it afterwards" }
    }

    /**
     * Registers a supplied change on top of the block's existing changes. Does not write to the world.
     * The change never holds inventory items: the external edit decided what became of them.
     */
    fun add(change: Change) {
        checkNotRestoring(change.block)
        addChange(change, takesInventory = false, applied = false, appliedMarker = null)
    }

    /**
     * [add]. From [apply] ([applied]), the block's marker (if it is the block's first change) was
     * already spawned as [appliedMarker], null when Paper refused it, and [takesInventory] says whether
     * apply took the live items (see [AlteredBlock.push]).
     */
    private fun addChange(change: Change, takesInventory: Boolean, applied: Boolean, appliedMarker: Entity?) {
        val isNew = !alteredBlocks.containsKey(change.block)
        val alteredBlock = alteredBlocks.computeIfAbsent(change.block) { AlteredBlock(change) }
        alteredBlock.push(change, takesInventory)
        blocksByName.getOrPut(change.internalName) { HashSet() }.add(change.block)
        for (cause in change.causes) blocksByCause.getOrPut(cause) { HashSet() }.add(change.block)
        if (isNew) {
            indexBlock(change.block)
            alteredBlock.marker = if (applied) appliedMarker
            else spawnMarker(change.block, change.fromData, alteredBlock.restoreAt(0).tileData())
        }
    }

    private fun indexBlock(block: Block) {
        blocksByChunk.getOrPut(block.world.uid) { HashMap() }
            .getOrPut(chunkKey(block.x shr 4, block.z shr 4)) { HashSet() }
            .add(block)
    }

    /** Blocks held by the chunk index; equals the tracked block count when the index is in step. */
    internal val indexedBlockCount: Int
        get() = blocksByChunk.values.sumOf { chunks -> chunks.values.sumOf { it.size } }

    private fun unindexBlock(block: Block) {
        val worldId = block.world.uid
        val chunks = blocksByChunk[worldId] ?: return
        val key = chunkKey(block.x shr 4, block.z shr 4)
        val blocks = chunks[key] ?: return
        blocks.remove(block)
        if (blocks.isEmpty()) {
            chunks.remove(key)
            if (chunks.isEmpty()) blocksByChunk.remove(worldId)
        }
    }

    /** Drops the removed [change]'s name and cause entries for its block once no remaining change uses them. */
    private fun unindexChange(change: Change, remaining: List<Change>) {
        val block = change.block
        if (remaining.none { it.internalName == change.internalName }) {
            unindexKey(blocksByName, change.internalName, block)
        }
        for (cause in change.causes) {
            if (remaining.none { cause in it.causes }) unindexKey(blocksByCause, cause, block)
        }
    }

    private fun unindexKey(index: MutableMap<String, MutableSet<Block>>, key: String, block: Block) {
        val blocks = index[key] ?: return
        blocks.remove(block)
        if (blocks.isEmpty()) index.remove(key)
    }

    /** Block entries held by the name index, summed over names; for tests. */
    internal val nameIndexedBlockCount: Int
        get() = blocksByName.values.sumOf { it.size }

    /** Block entries held by the cause index, summed over causes; for tests. */
    internal val causeIndexedBlockCount: Int
        get() = blocksByCause.values.sumOf { it.size }

    /** Registers a supplied change and undoes it after [durationTicks] ticks with [applyPhysics]. */
    fun add(change: Change, applyPhysics: Boolean, durationTicks: Long) {
        require(durationTicks > 0) { "durationTicks must be positive" }
        add(change)
        scheduleUndo(change, applyPhysics, durationTicks)
    }

    /**
     * Captures the current block, applies [newData], and registers a reversible [Change]
     * carrying [causes].
     */
    @JvmOverloads
    fun apply(
        internalName: String, block: Block, newData: BlockData, applyPhysics: Boolean,
        causes: Set<String> = emptySet(),
    ): Change {
        AlteredBlockCauses.requireValid(causes)
        checkNotRestoring(block)
        val fromData = block.blockData
        val fromState = block.state
        val first = !isTracked(block)
        // Guarded until registered: the marker spawn and the block write call other plugins
        // synchronously (EntitySpawnEvent, BlockPhysicsEvent), which must not edit this block meanwhile.
        return whileRestoring(block) {
            var marker: Entity? = null
            var tookItems = false
            if (first) {
                // The recovery marker comes first: only once Paper has accepted it may the live items
                // move into the change's custody. If it was refused, Paper handles them as without the
                // manager (they drop when the block entity goes).
                marker = spawnMarker(block, fromData, null).takeIf { it.isValid }
                if (marker == null) {
                    logger.warning("Recovery marker for altered block at ${block.location} was refused; " +
                        "leaving its items to Paper")
                } else {
                    // After the capture: the snapshot keeps the items.
                    tookItems = emptyLiveInventory(block, newData)
                    val tileData = if (tookItems) serializeTileData(fromState) else serializeTileDataWithoutItems(fromState)
                    if (tileData != null) marker.persistentDataContainer.set(blockStateKey, PersistentDataType.STRING, tileData)
                }
            }
            block.setBlockData(newData, applyPhysics)
            val change = Change(internalName, block, fromData, fromState, causes.toSet())
            addChange(change, takesInventory = tookItems, applied = true, appliedMarker = marker)
            change
        }
    }

    /**
     * Empties the live inventory of a block with an inventory that is about to become another block
     * type, so Paper does not drop its items on the ground when the block entity goes; the change's
     * captured snapshot holds them and undo writes them back. Call after capturing [Block.getState].
     * A change to the same block type keeps the block entity and its items, so nothing is emptied.
     * A chest's live inventory spans both halves of a double chest while its snapshot covers only its
     * own block, so only that block's inventory is emptied. A jukebox keeps its record (see
     * [serializeTileData]), which Paper drops as it would without the manager. Returns whether it
     * took the items, which decides whether the change holds them.
     */
    private fun emptyLiveInventory(block: Block, newData: BlockData): Boolean {
        if (newData.material == block.type) return false
        when (val live = block.getState(false)) {
            is Jukebox -> return false
            is Chest -> live.blockInventory.clear()
            is TileStateInventoryHolder -> live.inventory.clear()
            else -> return false
        }
        return true
    }

    /** [apply] with a single [cause]. */
    fun apply(internalName: String, block: Block, newData: BlockData, applyPhysics: Boolean, cause: String): Change =
        apply(internalName, block, newData, applyPhysics, setOf(cause))

    /**
     * Captures the current block, applies [material]'s default BlockData, and registers a reversible
     * [Change] carrying [causes].
     */
    @JvmOverloads
    fun apply(
        internalName: String, block: Block, material: Material, applyPhysics: Boolean,
        causes: Set<String> = emptySet(),
    ): Change = apply(internalName, block, material.createBlockData(), applyPhysics, causes)

    /** [apply] with a single [cause]. */
    fun apply(internalName: String, block: Block, material: Material, applyPhysics: Boolean, cause: String): Change =
        apply(internalName, block, material.createBlockData(), applyPhysics, setOf(cause))

    /** Applies a change and undoes it after [durationTicks] server ticks, using [applyPhysics]. */
    @JvmOverloads
    fun apply(
        internalName: String, block: Block, material: Material, applyPhysics: Boolean, durationTicks: Long,
        causes: Set<String> = emptySet(),
    ): Change = apply(internalName, block, material.createBlockData(), applyPhysics, durationTicks, causes)

    /** Timed [apply] with a single [cause]. */
    fun apply(
        internalName: String, block: Block, material: Material, applyPhysics: Boolean, durationTicks: Long,
        cause: String,
    ): Change = apply(internalName, block, material.createBlockData(), applyPhysics, durationTicks, setOf(cause))

    /** Applies a change and undoes it after [durationTicks] server ticks, using [applyPhysics]. */
    @JvmOverloads
    fun apply(
        internalName: String, block: Block, newData: BlockData, applyPhysics: Boolean, durationTicks: Long,
        causes: Set<String> = emptySet(),
    ): Change {
        require(durationTicks > 0) { "durationTicks must be positive" }
        val change = apply(internalName, block, newData, applyPhysics, causes)
        scheduleUndo(change, applyPhysics, durationTicks)
        return change
    }

    /** Timed [apply] with a single [cause]. */
    fun apply(
        internalName: String, block: Block, newData: BlockData, applyPhysics: Boolean, durationTicks: Long,
        cause: String,
    ): Change = apply(internalName, block, newData, applyPhysics, durationTicks, setOf(cause))

    private fun scheduleUndo(change: Change, applyPhysics: Boolean, durationTicks: Long) {
        pendingUndos.remove(change)?.cancel()
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (pendingUndos[change] === task) {
                pendingUndos.remove(change)
                change.undo(applyPhysics)
            }
        }, durationTicks)
        pendingUndos[change] = task
    }

    /**
     * Registers a reversible [Change] carrying [causes] for a block that has already been modified
     * (e.g. by WorldEdit). Does not write to the world.
     */
    @JvmOverloads
    fun register(
        internalName: String, block: Block, fromData: BlockData, fromState: BlockState,
        causes: Set<String> = emptySet(),
    ): Change {
        val change = Change(internalName, block, fromData, fromState, causes.toSet())
        add(change)
        return change
    }

    /** [register] with a single [cause]. */
    fun register(internalName: String, block: Block, fromData: BlockData, fromState: BlockState, cause: String): Change =
        register(internalName, block, fromData, fromState, setOf(cause))

    /** Registers an external edit and schedules restoration with [applyPhysics] after [durationTicks] ticks. */
    @JvmOverloads
    fun register(
        internalName: String, block: Block, fromData: BlockData, fromState: BlockState,
        applyPhysics: Boolean, durationTicks: Long, causes: Set<String> = emptySet(),
    ): Change {
        require(durationTicks > 0) { "durationTicks must be positive" }
        val change = register(internalName, block, fromData, fromState, causes)
        scheduleUndo(change, applyPhysics, durationTicks)
        return change
    }

    /** Timed [register] with a single [cause]. */
    fun register(
        internalName: String, block: Block, fromData: BlockData, fromState: BlockState,
        applyPhysics: Boolean, durationTicks: Long, cause: String,
    ): Change = register(internalName, block, fromData, fromState, applyPhysics, durationTicks, setOf(cause))

    /**
     * Undoes every tracked change (called on plugin disable so a reload does not leave blocks
     * altered). Markers are removed with each restore; any marker that survives a crash is restored
     * when its entities next load.
     */
    fun restoreAll() {
        val changes = alteredBlocks.values.flatMap { it.changes }
        for (change in changes.reversed()) {
            try {
                change.undo(false)
            } catch (e: RuntimeException) {
                logger.severe("Failed to restore altered block at ${change.block.location}: ${e.message}")
            }
        }
    }

    fun isTracked(block: Block): Boolean = alteredBlocks.containsKey(block)

    /**
     * Runs [listener] once, on the server thread, right after the active [change] has been undone:
     * a timed expiry, an explicit undo, an undo-by-name/cause, [restoreAll], or being spliced out
     * beneath a newer change. For cleaning up what a feature attached to the change (an entity
     * showing it); the feature must not keep its own expiry. Not persisted: after a restart only
     * the marker restores the block, so attach only non-persistent things.
     * Throws [IllegalStateException] when [change] is not active.
     */
    fun onUndone(change: Change, listener: Runnable) {
        val active = alteredBlocks[change.block]?.indexOf(change)?.let { it >= 0 } == true
        check(active) { "Change at ${change.block.location} is not active" }
        undoListeners.getOrPut(change) { ArrayList() }.add(listener)
    }

    /**
     * Makes undoing [change] leave the world alone if the block no longer holds what [change] wrote:
     * something replaced it behind the manager's back (WorldEdit, another plugin). The change is then
     * only spliced out, so a later edit is not overwritten by a stale restore. Judged when the change is
     * newest at undo time, against the block data as of this call (call right after applying).
     * Throws [IllegalStateException] when [change] is not active.
     */
    fun preserveExternalEdits(change: Change) {
        val active = alteredBlocks[change.block]?.indexOf(change)?.let { it >= 0 } == true
        check(active) { "Change at ${change.block.location} is not active" }
        preservedData[change] = change.block.blockData
    }

    private fun fireUndone(change: Change) {
        val listeners = undoListeners.remove(change) ?: return
        for (l in listeners) {
            try {
                l.run()
            } catch (e: RuntimeException) {
                logger.severe("Undo listener for altered block at ${change.block.location} failed: ${e.message}")
            }
        }
    }

    /**
     * Every active change under [internalName], oldest first within each block; blocks in no
     * particular order. Cost grows with the blocks that have such a change, not every tracked block.
     */
    fun getByInternalName(internalName: String): List<Change> =
        collectChanges(blocksByName[internalName]) { it.internalName == internalName }

    /** [getByCause] for a single [cause]. */
    fun getByCause(cause: String): List<Change> = getByCause(setOf(cause))

    /**
     * Every active change matching at least one of [causes] (see [AlteredBlockCauses]), oldest first
     * within each block; blocks in no particular order. Cost grows with the blocks that have such a
     * change. Throws [IllegalArgumentException] on an unknown cause.
     */
    fun getByCause(causes: Collection<String>): List<Change> {
        val matching = AlteredBlockCauses.matching(causes)
        val blocks = LinkedHashSet<Block>()
        for (cause in matching) blocksByCause[cause]?.let { blocks.addAll(it) }
        return collectChanges(blocks) { change -> change.causes.any { it in matching } }
    }

    private inline fun collectChanges(blocks: Collection<Block>?, filter: (Change) -> Boolean): List<Change> {
        val result = ArrayList<Change>()
        if (blocks == null) return result
        for (block in blocks) {
            val alteredBlock = alteredBlocks[block] ?: continue
            for (change in alteredBlock.changes) {
                if (filter(change)) result.add(change)
            }
        }
        return result
    }

    /** Every active change on [block], oldest first. */
    fun getByBlock(block: Block): List<Change> {
        val alteredBlock = alteredBlocks[block] ?: return emptyList()
        return ArrayList(alteredBlock.changes)
    }

    /**
     * Tracked blocks in [world] inside the inclusive block-coordinate box that have at least one
     * change under one of [internalNames]. Each block appears once, in no particular order.
     *
     * Cost grows with the tracked blocks in the chunks the box overlaps, not with every tracked
     * block on the server. Callers needing a sphere or cylinder query a box around it and apply
     * their exact shape check to the result. Pass a [Set] when there are many names.
     */
    fun getBlocksInBox(
        world: World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        internalNames: Collection<String>,
    ): List<Block> {
        if (internalNames.isEmpty()) return ArrayList()
        return blocksInBox(world, minX, minY, minZ, maxX, maxY, maxZ) { it.internalName in internalNames }
    }

    /** [getBlocksInBoxByCause] for a single [cause]. */
    fun getBlocksInBoxByCause(
        world: World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        cause: String,
    ): List<Block> = getBlocksInBoxByCause(world, minX, minY, minZ, maxX, maxY, maxZ, setOf(cause))

    /**
     * Like [getBlocksInBox], but matches blocks with at least one change matching one of [causes]
     * (see [AlteredBlockCauses]). Throws [IllegalArgumentException] on an unknown cause.
     */
    fun getBlocksInBoxByCause(
        world: World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        causes: Collection<String>,
    ): List<Block> {
        val matching = AlteredBlockCauses.matching(causes)
        if (matching.isEmpty()) return ArrayList()
        return blocksInBox(world, minX, minY, minZ, maxX, maxY, maxZ) { change -> change.causes.any { it in matching } }
    }

    private fun blocksInBox(
        world: World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        matches: (Change) -> Boolean,
    ): List<Block> {
        val result = ArrayList<Block>()
        if (minX > maxX || minY > maxY || minZ > maxZ) return result
        val chunks = blocksByChunk[world.uid] ?: return result
        val minCx = minX shr 4
        val maxCx = maxX shr 4
        val minCz = minZ shr 4
        val maxCz = maxZ shr 4

        fun collect(blocks: Set<Block>) {
            for (block in blocks) {
                if (block.x < minX || block.x > maxX || block.y < minY || block.y > maxY ||
                    block.z < minZ || block.z > maxZ) continue
                val changes = alteredBlocks[block]?.changes ?: continue
                if (changes.any(matches)) result.add(block)
            }
        }

        val boxChunks = (maxCx.toLong() - minCx + 1) * (maxCz.toLong() - minCz + 1)
        if (boxChunks <= chunks.size) {
            for (cx in minCx..maxCx) {
                for (cz in minCz..maxCz) {
                    chunks[chunkKey(cx, cz)]?.let { collect(it) }
                }
            }
        } else {
            // A box wider than the indexed area: walk the indexed chunks instead.
            for ((key, blocks) in chunks) {
                val cx = (key shr 32).toInt()
                val cz = key.toInt()
                if (cx in minCx..maxCx && cz in minCz..maxCz) collect(blocks)
            }
        }
        return result
    }

    fun getByBlockAndInternalName(block: Block, internalName: String): List<Change> {
        val alteredBlock = alteredBlocks[block] ?: return emptyList()
        return alteredBlock.changes.filter { it.internalName == internalName }
    }

    /**
     * Undoes every change registered under [internalName] (newest first per block).
     */
    fun undoAll(internalName: String, applyPhysics: Boolean) {
        undoNewestFirst(getByInternalName(internalName), applyPhysics)
    }

    /** [undoAllByCause] for a single [cause]. */
    fun undoAllByCause(cause: String, applyPhysics: Boolean) {
        undoAllByCause(setOf(cause), applyPhysics)
    }

    /**
     * Undoes every change matching at least one of [causes] (newest first per block). Changes with
     * other causes stay in place: where one sits on top, the block keeps showing it, and undoing it
     * later restores the state from before the undone changes.
     */
    fun undoAllByCause(causes: Collection<String>, applyPhysics: Boolean) {
        undoNewestFirst(getByCause(causes), applyPhysics)
    }

    /** [undoByCause] for a single [cause]. */
    fun undoByCause(block: Block, cause: String, applyPhysics: Boolean): Boolean =
        undoByCause(block, setOf(cause), applyPhysics)

    /**
     * Undoes every change on [block] matching one of [causes] (newest first), leaving its other
     * changes in place. True if anything was undone. Throws [IllegalArgumentException] on an unknown cause.
     */
    fun undoByCause(block: Block, causes: Collection<String>, applyPhysics: Boolean): Boolean {
        val matching = AlteredBlockCauses.matching(causes)
        val changes = alteredBlocks[block]?.changes?.filter { change -> change.causes.any { it in matching } }
        if (changes.isNullOrEmpty()) return false
        undoNewestFirst(changes, applyPhysics)
        return true
    }

    /** [previewUndoByCause] for a single [cause]. */
    fun previewUndoByCause(block: Block, cause: String): BlockData? = previewUndoByCause(block, setOf(cause))

    /**
     * The BlockData [block] would show after [undoByCause] with [causes], without changing anything;
     * null when no change on it matches. When a non-matching change is newest, that is the block's
     * current data. Throws [IllegalArgumentException] on an unknown cause.
     */
    fun previewUndoByCause(block: Block, causes: Collection<String>): BlockData? {
        val matching = AlteredBlockCauses.matching(causes)
        val alteredBlock = alteredBlocks[block] ?: return null
        val changes = alteredBlock.changes
        if (changes.none { change -> change.causes.any { it in matching } }) return null
        val keptTop = changes.indexOfLast { change -> change.causes.none { it in matching } }
        return when (keptTop) {
            -1 -> alteredBlock.originalBlockData.clone()
            changes.size - 1 -> block.blockData
            else -> alteredBlock.restoreDataAt(keptTop + 1).clone()
        }
    }

    private fun undoNewestFirst(changes: List<Change>, applyPhysics: Boolean) {
        for (i in changes.indices.reversed()) {
            changes[i].undo(applyPhysics)
        }
    }

    private fun undo(change: Change, applyPhysics: Boolean) {
        val block = change.block
        if (block in restoring) {
            // Called back from inside this block's own restore: the stack is mid-update.
            logger.warning("Ignored an undo of altered block at ${block.location} while it is being changed or restored")
            return
        }
        pendingUndos.remove(change)?.cancel()
        val alteredBlock = alteredBlocks[block] ?: return
        val index = alteredBlock.indexOf(change)
        if (index < 0) return
        val expected = preservedData.remove(change)
        val lastIndex = alteredBlock.changes.size - 1
        if (index == lastIndex) {
            // The entry stays until the restore succeeds, so a failed undo can be retried; a retry
            // does not put the items back twice (see restoredKey).
            whileRestoring(block) {
                if (expected == null || block.blockData == expected) {
                    restoreBlockState(block, alteredBlock.restoreAt(index), applyPhysics, alteredBlock.marker?.uniqueId)
                } else {
                    // replaced behind our back: that edit stands, the stale restore is dropped
                    logger.info("Altered block at ${block.location} was replaced externally; leaving it as is")
                }
                alteredBlock.removeAt(index)
                if (lastIndex == 0) {
                    removeMarker(alteredBlock)
                    alteredBlocks.remove(block)
                    unindexBlock(block)
                }
            }
        } else {
            // An older change: the block keeps showing the newer ones; the change above it now
            // restores to what this one would have.
            alteredBlock.removeAt(index)
        }
        unindexChange(change, alteredBlock.changes)
        fireUndone(change)
    }

    private fun restoreBlockState(block: Block, restore: Restore, applyPhysics: Boolean, markerId: UUID?) {
        block.setBlockData(restore.data, applyPhysics)
        val tileData = restore.tileData()
        if (tileData != null) {
            if (markerId != null) writeTileData(block, tileData, applyPhysics, markerId)
            else applySerializedTileData(block, tileData, applyPhysics)
        }
    }

    /**
     * [applySerializedTileData] for tile data held by [markerId]'s change, whose items it took out of
     * the world. The items go back at most once: with them the block entity gets [restoredKey] set
     * to [markerId], and if it already has that, they are back and are left out. They never
     * overwrite a live inventory that still holds items (a block entity that survived because a
     * later change placed the same block type again and players filled it): those stay, and the
     * saved items drop at the block instead.
     */
    private fun writeTileData(block: Block, json: String, applyPhysics: Boolean, markerId: UUID) {
        val root = JsonParser.parseString(json).asJsonObject
        if (!root.has("items")) {
            applySerializedTileData(block, json, applyPhysics)
            return
        }
        if (itemsRestored(block, markerId)) {
            root.remove("items")
            applyTileData(block, root, applyPhysics, null)
            return
        }
        val live = when (val state = block.getState(false)) {
            is Chest -> state.blockInventory
            is TileStateInventoryHolder -> state.inventory
            else -> null
        }
        var displaced: Array<ItemStack?>? = null
        if (live != null && !live.isEmpty) {
            displaced = decodeItems(root.get("items").asString)
            root.remove("items")
        }
        applyTileData(block, root, applyPhysics, restoredKey to markerId.toString())
        if (displaced != null) {
            val at = block.location.add(0.5, 0.5, 0.5)
            for (item in displaced) {
                if (item != null && !item.type.isAir) block.world.dropItemNaturally(at, item)
            }
        }
    }

    /** Whether [block]'s block entity already got the items of [markerId]'s change back (see [restoredKey]). */
    private fun itemsRestored(block: Block, markerId: UUID): Boolean {
        val state = block.getState(false) as? TileState ?: return false
        return state.persistentDataContainer.get(restoredKey, PersistentDataType.STRING) == markerId.toString()
    }

    /**
     * Resolves the block this marker was created for (from PDC), falling back to the entity's current block.
     */
    fun getMarkedBlock(entity: Entity?): Block? {
        if (entity == null) return null
        val pdc = entity.persistentDataContainer
        val worldId = pdc.get(worldKey, PersistentDataType.STRING)
        val x = pdc.get(xKey, PersistentDataType.INTEGER)
        val y = pdc.get(yKey, PersistentDataType.INTEGER)
        val z = pdc.get(zKey, PersistentDataType.INTEGER)
        if (worldId != null && x != null && y != null && z != null) {
            try {
                val world = Bukkit.getWorld(UUID.fromString(worldId))
                if (world != null) return world.getBlockAt(x, y, z)
                logger.severe("Altered-block marker references missing world $worldId")
                return null
            } catch (_: IllegalArgumentException) {
                logger.severe("Altered-block marker has invalid world UUID: $worldId")
                return null
            }
        }
        return entity.location.block
    }

    /**
     * Restores a block from an orphan marker (prior session) and removes the entity.
     */
    fun restoreFromMarker(entity: Entity?) {
        if (entity == null || !entity.isValid) return
        if (!entity.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG)) return

        val block = getMarkedBlock(entity)
        if (block != null && block in restoring) return
        if (block != null) whileRestoring(block) { restoreFromMarker(entity, block) } else restoreFromMarker(entity, null)
    }

    private fun restoreFromMarker(entity: Entity, block: Block?) {
        val pdc = entity.persistentDataContainer
        val current = pdc.has(formatKey, PersistentDataType.INTEGER)
        if (current && block != null && itemsRestored(block, entity.uniqueId)) {
            // Its change was undone (or this restore already ran) but the marker was not loaded to
            // be removed: the block already holds what it would write.
            entity.remove()
            return
        }
        val dataString = pdc.get(blockDataKey, PersistentDataType.STRING)
        if (block != null && dataString != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(dataString), false)
            } catch (_: IllegalArgumentException) {
                logger.severe("Invalid altered-block marker BlockData at ${block.location}: $dataString")
            }

            val stateString = pdc.get(blockStateKey, PersistentDataType.STRING)
            if (stateString != null) {
                try {
                    if (current) {
                        writeTileData(block, stateString, false, entity.uniqueId)
                    } else {
                        // An older build's marker: its items may still be in the block, so write the
                        // payload exactly as that build did (see applyLegacyTileData), dropping nothing.
                        applyLegacyTileData(block, stateString, false)
                    }
                } catch (e: RuntimeException) {
                    logger.severe("Invalid altered-block marker tile data at ${block.location}: ${e.message}")
                }
            }
        }
        entity.remove()
    }

    private fun spawnMarker(block: Block, originalData: BlockData, tileData: String?): Marker {
        val loc: Location = block.location.add(0.5, 0.5, 0.5)
        val marker = block.world.spawnEntity(loc, EntityType.MARKER) as Marker
        marker.isPersistent = true
        marker.setGravity(false)
        marker.isSilent = true
        marker.isInvulnerable = true
        marker.addScoreboardTag(SneakyUtils.ALTERED_BLOCK_TAG)
        val pdc = marker.persistentDataContainer
        pdc.set(formatKey, PersistentDataType.INTEGER, MARKER_FORMAT)
        pdc.set(blockDataKey, PersistentDataType.STRING, originalData.asString)
        if (tileData != null) {
            pdc.set(blockStateKey, PersistentDataType.STRING, tileData)
        }
        pdc.set(worldKey, PersistentDataType.STRING, block.world.uid.toString())
        pdc.set(xKey, PersistentDataType.INTEGER, block.x)
        pdc.set(yKey, PersistentDataType.INTEGER, block.y)
        pdc.set(zKey, PersistentDataType.INTEGER, block.z)
        return marker
    }

    /**
     * Removes [alteredBlock]'s marker. After its chunk unloaded and loaded again, the held entity is a
     * stale object, so the live one is looked up by UUID. If it is not loaded right now, its UUID is
     * remembered and the marker is discarded, without restoring, when it next loads in this session.
     */
    private fun removeMarker(alteredBlock: AlteredBlock) {
        val marker = alteredBlock.marker
        if (marker != null) {
            val live = if (marker.isValid) marker else Bukkit.getEntity(marker.uniqueId)?.takeIf { it.isValid }
            if (live != null) {
                live.remove()
            } else {
                rememberRemovedMarker(marker.uniqueId)
                retryMarkerRemoval(marker.uniqueId, marker.location)
            }
        }
        alteredBlock.marker = null
    }

    /**
     * Looks for the unloaded marker [id] every tick for [markerRetryTicks] and removes it once it is
     * loaded (a chunk's entities load just after the chunk), so a restart has as small a window as
     * possible to find it still there. Not while the plugin is disabling (restoreAll on shutdown).
     */
    private fun retryMarkerRemoval(id: UUID, location: Location) {
        if (!plugin.isEnabled) return
        var ticks = 0L
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val entity = Bukkit.getEntity(id)
            if (entity != null && entity.isValid) {
                entity.remove()
                removedMarkerIds.remove(id)
                task.cancel()
            } else if (id !in removedMarkerIds) {
                task.cancel()
            } else if (++ticks >= markerRetryTicks) {
                // Its chunk's entities are loaded and it is not among them: it was deleted (an admin,
                // a cleanup plugin) and can never load, so stop waiting for it. Otherwise keep it.
                if (entitiesLoaded(location)) removedMarkerIds.remove(id)
                task.cancel()
            }
        }, 1L, 1L)
    }

    private fun entitiesLoaded(location: Location): Boolean {
        val world = location.world ?: return false
        val cx = location.blockX shr 4
        val cz = location.blockZ shr 4
        return world.isChunkLoaded(cx, cz) && world.getChunkAt(cx, cz).isEntitiesLoaded
    }

    private fun rememberRemovedMarker(id: UUID) {
        removedMarkerIds.add(id)
        if (removedMarkerIds.size <= removedMarkerCap) return
        val oldest = removedMarkerIds.iterator().next()
        removedMarkerIds.remove(oldest)
        logger.warning("Forgot removed altered-block marker $oldest (over $removedMarkerCap pending); " +
            "if it loads it restores its block once more")
    }

    /**
     * When [entity] is a marker this session already removed while it was unloaded, removes it and
     * returns true; the listener then skips restoring from it. Called as marker entities load.
     */
    internal fun discardRemovedMarker(entity: Entity): Boolean {
        if (!removedMarkerIds.remove(entity.uniqueId)) return false
        if (entity.isValid) entity.remove()
        return true
    }

    /**
     * One tracked block: its original state (also stored on the marker) and its active changes,
     * oldest first, each with the state undoing it writes back.
     */
    class AlteredBlock(change: Change) {
        val originalBlockData: BlockData = change.fromData
        val originalBlockState: BlockState = change.fromState
        private val entries: MutableList<Change> = ArrayList()
        private val restoreTo: MutableList<Restore> = ArrayList()

        /** Active changes, oldest first (read-only view). */
        val changes: List<Change> get() = entries
        var marker: Entity? = null

        /**
         * Adds [change], whose new block is already in the world (after apply's write, or after the
         * external edit a register/add records). Only a change whose apply actually took the live items
         * ([takesInventory]: the block's first change, its marker accepted, the material changing)
         * holds them: see [Restore]. A register/add change never does; the external edit
         * already decided what became of the items (dropped, kept, moved).
         */
        internal fun push(change: Change, takesInventory: Boolean) {
            val first = entries.isEmpty()
            entries.add(change)
            restoreTo.add(Restore(change.fromData, change.fromState, takesInventory && first))
        }

        internal fun indexOf(change: Change): Int = entries.indexOfFirst { it === change }

        internal fun restoreAt(index: Int): Restore = restoreTo[index]

        /** The BlockData undoing the change at [index] writes back if it is the newest change. */
        internal fun restoreDataAt(index: Int): BlockData = restoreTo[index].data

        /**
         * Removes the change at [index] and returns what it would restore. A change above it
         * inherits that (including the first change's captured items), so the stack still unwinds
         * to the original and the items are written back at most once.
         */
        internal fun removeAt(index: Int): Restore {
            entries.removeAt(index)
            val target = restoreTo.removeAt(index)
            if (index < restoreTo.size) restoreTo[index] = target
            return target
        }
    }

    /**
     * What undoing a change writes back: [data], the tile data of [state], and, when [takeItems],
     * the inventory items captured in [state]. Without [takeItems] the live inventory is left as it
     * is: the captured block had no inventory; or the change kept the block's material (a chest
     * turned to face east), so the block entity and its items stayed in place and writing the
     * captured items back would return what was taken out since; or the block was already changed,
     * so the change left the inventory to Paper, which drops it when the block entity goes.
     */
    internal class Restore(val data: BlockData, val state: BlockState, val takeItems: Boolean) {
        fun tileData(): String? = if (takeItems) serializeTileData(state) else serializeTileDataWithoutItems(state)
    }

    /**
     * Java-compatible record-style accessors (`internalName()`, `block()`, `causes()`, …) via [JvmRecord].
     * [fromData]/[fromState] are what the block was when this change was made; [causes] say what
     * caused it, from [AlteredBlockCauses] (any other string throws [IllegalArgumentException]).
     * Treat the set as immutable once registered.
     */
    @JvmRecord
    data class Change @JvmOverloads constructor(
        val internalName: String,
        val block: Block,
        val fromData: BlockData,
        val fromState: BlockState,
        val causes: Set<String> = emptySet(),
    ) {
        init {
            AlteredBlockCauses.requireValid(causes)
        }

        /** Change with a single [cause]. */
        constructor(internalName: String, block: Block, fromData: BlockData, fromState: BlockState, cause: String) :
            this(internalName, block, fromData, fromState, setOf(cause))

        /** Whether this change matches [cause], following [AlteredBlockCauses]' implications. */
        fun hasCause(cause: String): Boolean = hasAnyCause(setOf(cause))

        /** Whether this change matches any of [causes], following [AlteredBlockCauses]' implications. */
        fun hasAnyCause(causes: Collection<String>): Boolean {
            val matching = AlteredBlockCauses.matching(causes)
            return this.causes.any { it in matching }
        }

        /**
         * Undoes this change. When it is the block's newest change, the block goes back to the state
         * this change replaced; otherwise the world is left alone and the change is spliced out.
         */
        fun undo(applyPhysics: Boolean) {
            SneakyUtils.getAlteredBlockManager().undo(this, applyPhysics)
        }
    }

    companion object {
        private val COMPONENT_JSON = GsonComponentSerializer.gson()

        /** Marker format written by this build; see [formatKey]. */
        private const val MARKER_FORMAT = 2

        private fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

        /**
         * [serializeTileData] of [state] without its items, for a restore that leaves the live
         * inventory alone: a lectern keeps only its page, other inventory holders have nothing to
         * write. The inventory is never encoded.
         */
        internal fun serializeTileDataWithoutItems(state: BlockState): String? = when (state) {
            is Lectern -> JsonObject().apply {
                addProperty("v", 1)
                addProperty("kind", "lectern")
                addProperty("page", state.page)
            }.toString()
            is TileStateInventoryHolder -> null
            else -> serializeTileData(state)
        }

        /**
         * Serializes tile-entity state for cross-session marker restore (signs, containers, etc.).
         * Returns null when there is nothing beyond BlockData to store.
         *
         * Items of every block with an inventory (any [TileStateInventoryHolder]: containers, chiseled
         * bookshelves, decorated pots, shelves; the lectern also keeps its page; not a jukebox) are read
         * from the state's snapshot inventory. On a placed state, getInventory() reads the live block,
         * which no longer holds them once the block has been changed.
         */
        @JvmStatic
        fun serializeTileData(state: BlockState?): String? {
            if (state == null) return null

            val root = JsonObject()
            root.addProperty("v", 1)

            when (state) {
                is Sign -> {
                    root.addProperty("kind", "sign")
                    root.add("front", serializeSignSide(state.getSide(Side.FRONT)))
                    root.add("back", serializeSignSide(state.getSide(Side.BACK)))
                    root.addProperty("waxed", state.isWaxed)
                }
                is Lectern -> {
                    root.addProperty("kind", "lectern")
                    root.addProperty("items", encodeItems(state.snapshotInventory.contents))
                    root.addProperty("page", state.page)
                }
                // Not saved, as before this build: on Paper every way of writing a record back
                // (CraftJukebox.update's setBlockAndUpdate, setRecord's updateNeighborsAt) updates
                // neighbours whatever the physics flag.
                is Jukebox -> return null
                is TileStateInventoryHolder -> {
                    // "container": the kind every inventory holder uses (older records: Container only).
                    root.addProperty("kind", "container")
                    root.addProperty("items", encodeItems(state.snapshotInventory.contents))
                }
                is CreatureSpawner -> {
                    root.addProperty("kind", "spawner")
                    val spawned = state.spawnedType
                    if (spawned != null) {
                        root.addProperty("spawnedType", spawned.name)
                    }
                    root.addProperty("delay", state.delay)
                    root.addProperty("minSpawnDelay", state.minSpawnDelay)
                    root.addProperty("maxSpawnDelay", state.maxSpawnDelay)
                    root.addProperty("spawnCount", state.spawnCount)
                    root.addProperty("maxNearbyEntities", state.maxNearbyEntities)
                    root.addProperty("requiredPlayerRange", state.requiredPlayerRange)
                    root.addProperty("spawnRange", state.spawnRange)
                }
                is Banner -> {
                    root.addProperty("kind", "banner")
                    val patterns = JsonArray()
                    for (pattern in state.patterns) {
                        val entry = JsonObject()
                        entry.addProperty("color", pattern.color.name)
                        val key = RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.BANNER_PATTERN).getKey(pattern.pattern)
                        entry.addProperty("pattern", key?.toString() ?: pattern.pattern.toString())
                        patterns.add(entry)
                    }
                    root.add("patterns", patterns)
                }
                else -> return null
            }

            return root.toString()
        }

        private fun serializeSignSide(side: org.bukkit.block.sign.SignSide): JsonObject {
            val obj = JsonObject()
            val lines = JsonArray()
            for (i in 0 until 4) {
                lines.add(COMPONENT_JSON.serialize(side.line(i)))
            }
            obj.add("lines", lines)
            obj.addProperty("glowing", side.isGlowingText)
            val color = side.color
            if (color != null) {
                obj.addProperty("color", color.name)
            }
            return obj
        }

        private fun encodeItems(contents: Array<ItemStack?>): String =
            Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents))

        @Suppress("UNCHECKED_CAST")
        private fun decodeItems(encoded: String): Array<ItemStack?> =
            ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded)) as Array<ItemStack?>

        /**
         * Writes tile data from [serializeTileData] onto [block], which must already have the matching
         * BlockData. Items go into a fresh state's snapshot inventory, which update(true) then applies
         * (writing the live inventory would be overwritten by that update).
         */
        @JvmStatic
        fun applySerializedTileData(block: Block, json: String, applyPhysics: Boolean) {
            val root: JsonObject
            try {
                root = JsonParser.parseString(json).asJsonObject
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            }
            applyTileData(block, root, applyPhysics, null)
        }

        /**
         * origin/main's applySerializedTileData, kept for markers written by older builds (no format
         * key), so they recover exactly as they did: container and lectern items are written to the
         * state's live inventory and the state, whose snapshot was taken before that write, is then
         * updated, whatever the net effect on Paper. Every other kind takes the unchanged path.
         */
        internal fun applyLegacyTileData(block: Block, json: String, applyPhysics: Boolean) {
            val root: JsonObject
            try {
                root = JsonParser.parseString(json).asJsonObject
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            }

            val kind = if (root.has("kind")) root.get("kind").asString else ""
            when (kind) {
                "container" -> {
                    val state = block.state
                    if (state !is Container) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
                    state.update(true, applyPhysics)
                }
                "lectern" -> {
                    val state = block.state
                    if (state !is Lectern) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
                    if (root.has("page")) {
                        state.page = root.get("page").asInt
                    }
                    state.update(true, applyPhysics)
                }
                else -> applyTileData(block, root, applyPhysics, null)
            }
        }

        /**
         * [applySerializedTileData] on a parsed record. For an inventory record, [stamp] (a PDC key
         * and value) is written into the block entity in the same update as the items.
         */
        private fun applyTileData(
            block: Block, root: JsonObject, applyPhysics: Boolean, stamp: Pair<NamespacedKey, String>?,
        ) {
            val kind = if (root.has("kind")) root.get("kind").asString else ""
            val state = block.state

            when (kind) {
                "sign" -> {
                    if (state !is Sign) return
                    applySignSide(state.getSide(Side.FRONT), root.getAsJsonObject("front"))
                    applySignSide(state.getSide(Side.BACK), root.getAsJsonObject("back"))
                    if (root.has("waxed")) {
                        state.isWaxed = root.get("waxed").asBoolean
                    }
                    state.update(true, applyPhysics)
                }
                "container" -> {
                    if (state !is TileStateInventoryHolder) return
                    if (!root.has("items") && stamp == null) return
                    if (root.has("items")) setSnapshotItems(state, decodeItems(root.get("items").asString))
                    if (stamp != null) state.persistentDataContainer.set(stamp.first, PersistentDataType.STRING, stamp.second)
                    state.update(true, applyPhysics)
                }
                "lectern" -> {
                    if (state !is Lectern) return
                    // No items: a same-type change, which leaves the book where it is.
                    if (root.has("items")) setSnapshotItems(state, decodeItems(root.get("items").asString))
                    if (stamp != null) state.persistentDataContainer.set(stamp.first, PersistentDataType.STRING, stamp.second)
                    if (root.has("page")) {
                        state.page = root.get("page").asInt
                    }
                    state.update(true, applyPhysics)
                }
                "spawner" -> {
                    if (state !is CreatureSpawner) return
                    if (root.has("spawnedType")) {
                        try {
                            state.spawnedType = EntityType.valueOf(root.get("spawnedType").asString)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    if (root.has("delay")) state.delay = root.get("delay").asInt
                    if (root.has("minSpawnDelay")) state.minSpawnDelay = root.get("minSpawnDelay").asInt
                    if (root.has("maxSpawnDelay")) state.maxSpawnDelay = root.get("maxSpawnDelay").asInt
                    if (root.has("spawnCount")) state.spawnCount = root.get("spawnCount").asInt
                    if (root.has("maxNearbyEntities")) state.maxNearbyEntities = root.get("maxNearbyEntities").asInt
                    if (root.has("requiredPlayerRange")) state.requiredPlayerRange = root.get("requiredPlayerRange").asInt
                    if (root.has("spawnRange")) state.spawnRange = root.get("spawnRange").asInt
                    state.update(true, applyPhysics)
                }
                "banner" -> {
                    if (state !is Banner) return
                    val patterns = ArrayList<Pattern>()
                    val array = root.getAsJsonArray("patterns")
                    if (array != null) {
                        for (element in array) {
                            val entry = element.asJsonObject
                            val color = DyeColor.valueOf(entry.get("color").asString)
                            val patternId = entry.get("pattern").asString
                            val type = resolvePatternType(patternId)
                            if (type != null) {
                                patterns.add(Pattern(color, type))
                            }
                        }
                    }
                    state.patterns = patterns
                    state.update(true, applyPhysics)
                }
            }
        }

        /** Fills [state]'s snapshot inventory slot by slot (extra saved slots are ignored, missing ones emptied). */
        private fun setSnapshotItems(state: TileStateInventoryHolder, items: Array<ItemStack?>) {
            val inventory = state.snapshotInventory
            inventory.contents = Array(inventory.size) { items.getOrNull(it) }
        }

        private fun applySignSide(side: org.bukkit.block.sign.SignSide, obj: JsonObject?) {
            if (obj == null) return
            val lines = obj.getAsJsonArray("lines")
            if (lines != null) {
                for (i in 0 until minOf(4, lines.size())) {
                    val line = COMPONENT_JSON.deserialize(lines.get(i).asString)
                    side.line(i, line)
                }
            }
            if (obj.has("glowing")) {
                side.isGlowingText = obj.get("glowing").asBoolean
            }
            if (obj.has("color")) {
                try {
                    side.color = DyeColor.valueOf(obj.get("color").asString)
                } catch (_: IllegalArgumentException) {
                }
            }
        }

        private fun resolvePatternType(id: String?): PatternType? {
            if (id.isNullOrBlank()) return null
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN)
            val trimmed = id.trim()
            val key = NamespacedKey.fromString(
                if (trimmed.contains(":")) trimmed else "minecraft:${trimmed.lowercase()}"
            )
            if (key != null) {
                val typed = registry.get(key)
                if (typed != null) return typed
            }
            return registry.get(NamespacedKey.minecraft(trimmed.lowercase()))
        }
    }
}
