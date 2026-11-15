package com.sneakyutils.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.sneakyutils.SneakyUtils
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
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Lectern
import org.bukkit.block.Sign
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
 * Changes may carry causes (arbitrary strings such as `"explosion"` or `"damage"`), so tools can
 * find or undo changes by what caused them rather than by which feature made them.
 */
class AlteredBlockManager(private val plugin: JavaPlugin) {

    private val pendingUndos = IdentityHashMap<Change, BukkitTask>()
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

    /** Registers a supplied change on top of the block's existing changes. Does not write to the world. */
    fun add(change: Change) {
        val isNew = !alteredBlocks.containsKey(change.block)
        val alteredBlock = alteredBlocks.computeIfAbsent(change.block) { AlteredBlock(change) }
        alteredBlock.push(change)
        blocksByName.getOrPut(change.internalName) { HashSet() }.add(change.block)
        for (cause in change.causes) blocksByCause.getOrPut(cause) { HashSet() }.add(change.block)
        if (isNew) {
            indexBlock(change.block)
            alteredBlock.marker = spawnMarker(change.block, change.fromData, change.fromState)
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
        val fromData = block.blockData
        val fromState = block.state
        block.setBlockData(newData, applyPhysics)
        val change = Change(internalName, block, fromData, fromState, causes.toSet())
        add(change)
        return change
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
     * Every active change under [internalName], oldest first within each block; blocks in no
     * particular order. Cost grows with the blocks that have such a change, not every tracked block.
     */
    fun getByInternalName(internalName: String): List<Change> =
        collectChanges(blocksByName[internalName]) { it.internalName == internalName }

    /** [getByCause] for a single [cause]. */
    fun getByCause(cause: String): List<Change> = getByCause(setOf(cause))

    /**
     * Every active change carrying at least one of [causes], oldest first within each block; blocks
     * in no particular order. Cost grows with the blocks that have such a change.
     */
    fun getByCause(causes: Collection<String>): List<Change> {
        val blocks = LinkedHashSet<Block>()
        for (cause in causes) blocksByCause[cause]?.let { blocks.addAll(it) }
        return collectChanges(blocks) { it.hasAnyCause(causes) }
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
     * Like [getBlocksInBox], but matches blocks with at least one change carrying one of [causes].
     */
    fun getBlocksInBoxByCause(
        world: World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int,
        causes: Collection<String>,
    ): List<Block> {
        if (causes.isEmpty()) return ArrayList()
        return blocksInBox(world, minX, minY, minZ, maxX, maxY, maxZ) { it.hasAnyCause(causes) }
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
     * Undoes every change carrying at least one of [causes] (newest first per block). Changes with
     * other causes stay in place: where one sits on top, the block keeps showing it, and undoing it
     * later restores the state from before the undone changes.
     */
    fun undoAllByCause(causes: Collection<String>, applyPhysics: Boolean) {
        undoNewestFirst(getByCause(causes), applyPhysics)
    }

    private fun undoNewestFirst(changes: List<Change>, applyPhysics: Boolean) {
        for (i in changes.indices.reversed()) {
            changes[i].undo(applyPhysics)
        }
    }

    private fun undo(change: Change, applyPhysics: Boolean) {
        pendingUndos.remove(change)?.cancel()
        val block = change.block
        val alteredBlock = alteredBlocks[block] ?: return
        val index = alteredBlock.indexOf(change)
        if (index < 0) return
        val lastIndex = alteredBlock.changes.size - 1
        if (lastIndex == 0) {
            restoreBlockState(block, alteredBlock.originalBlockData, alteredBlock.originalBlockState, applyPhysics)
            removeMarker(alteredBlock)
            alteredBlocks.remove(block)
            unindexBlock(block)
            alteredBlock.removeAt(index)
        } else if (index == lastIndex) {
            val (data, state) = alteredBlock.removeAt(index)
            restoreBlockState(block, data, state, applyPhysics)
        } else {
            // An older change: the block keeps showing the newer ones; the change above it now
            // restores to what this one would have.
            alteredBlock.removeAt(index)
        }
        unindexChange(change, alteredBlock.changes)
    }

    private fun restoreBlockState(block: Block, blockData: BlockData, blockState: BlockState, applyPhysics: Boolean) {
        block.setBlockData(blockData, applyPhysics)
        val tileData = serializeTileData(blockState)
        if (tileData != null) {
            applySerializedTileData(block, tileData, applyPhysics)
        }
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
        val pdc = entity.persistentDataContainer
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
                    applySerializedTileData(block, stateString, false)
                } catch (e: RuntimeException) {
                    logger.severe("Invalid altered-block marker tile data at ${block.location}: ${e.message}")
                }
            }
        }
        entity.remove()
    }

    private fun spawnMarker(block: Block, originalData: BlockData, originalState: BlockState): Marker {
        val loc: Location = block.location.add(0.5, 0.5, 0.5)
        val marker = block.world.spawnEntity(loc, EntityType.MARKER) as Marker
        marker.isPersistent = true
        marker.setGravity(false)
        marker.isSilent = true
        marker.isInvulnerable = true
        marker.addScoreboardTag(SneakyUtils.ALTERED_BLOCK_TAG)
        val pdc = marker.persistentDataContainer
        pdc.set(blockDataKey, PersistentDataType.STRING, originalData.asString)
        val tileData = serializeTileData(originalState)
        if (tileData != null) {
            pdc.set(blockStateKey, PersistentDataType.STRING, tileData)
        }
        pdc.set(worldKey, PersistentDataType.STRING, block.world.uid.toString())
        pdc.set(xKey, PersistentDataType.INTEGER, block.x)
        pdc.set(yKey, PersistentDataType.INTEGER, block.y)
        pdc.set(zKey, PersistentDataType.INTEGER, block.z)
        return marker
    }

    private fun removeMarker(alteredBlock: AlteredBlock) {
        val marker = alteredBlock.marker
        if (marker != null && marker.isValid) {
            marker.remove()
        }
        alteredBlock.marker = null
    }

    /**
     * One tracked block: its original state (also stored on the marker) and its active changes,
     * oldest first, each with the state undoing it writes back.
     */
    class AlteredBlock(change: Change) {
        val originalBlockData: BlockData = change.fromData
        val originalBlockState: BlockState = change.fromState
        private val entries: MutableList<Change> = ArrayList()
        private val restoreTo: MutableList<Pair<BlockData, BlockState>> = ArrayList()

        /** Active changes, oldest first (read-only view). */
        val changes: List<Change> get() = entries
        var marker: Entity? = null

        internal fun push(change: Change) {
            entries.add(change)
            restoreTo.add(change.fromData to change.fromState)
        }

        internal fun indexOf(change: Change): Int = entries.indexOfFirst { it === change }

        /**
         * Removes the change at [index] and returns the state it would restore. A change above it
         * inherits that state, so the stack still unwinds to the original.
         */
        internal fun removeAt(index: Int): Pair<BlockData, BlockState> {
            entries.removeAt(index)
            val target = restoreTo.removeAt(index)
            if (index < restoreTo.size) restoreTo[index] = target
            return target
        }
    }

    /**
     * Java-compatible record-style accessors (`internalName()`, `block()`, `causes()`, …) via [JvmRecord].
     * [fromData]/[fromState] are what the block was when this change was made; [causes] are free-form
     * labels (e.g. `"explosion"`) for finding or undoing changes by what caused them. Treat the set
     * as immutable once registered.
     */
    @JvmRecord
    data class Change @JvmOverloads constructor(
        val internalName: String,
        val block: Block,
        val fromData: BlockData,
        val fromState: BlockState,
        val causes: Set<String> = emptySet(),
    ) {
        /** Change with a single [cause]. */
        constructor(internalName: String, block: Block, fromData: BlockData, fromState: BlockState, cause: String) :
            this(internalName, block, fromData, fromState, setOf(cause))

        fun hasCause(cause: String): Boolean = cause in causes

        fun hasAnyCause(causes: Collection<String>): Boolean = causes.any { it in this.causes }

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

        private fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

        /**
         * Serializes tile-entity state for cross-session marker restore (signs, containers, etc.).
         * Returns null when there is nothing beyond BlockData to store.
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
                is Container -> {
                    root.addProperty("kind", "container")
                    root.addProperty("items", encodeItems(state.inventory.contents))
                }
                is Lectern -> {
                    root.addProperty("kind", "lectern")
                    root.addProperty("items", encodeItems(state.inventory.contents))
                    root.addProperty("page", state.page)
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
                    if (state !is Container) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
                    state.update(true, applyPhysics)
                }
                "lectern" -> {
                    if (state !is Lectern) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
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
