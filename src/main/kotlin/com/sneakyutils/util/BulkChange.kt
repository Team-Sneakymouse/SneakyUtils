package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.BitSet
import java.util.UUID
import java.util.function.Function

/**
 * Many temporary block changes as one, for craters and other area effects that change thousands of blocks
 * ([AlteredBlockManager.bulk]). Each block costs a few bytes instead of a full [AlteredBlockManager.Change]
 * and its own record, and the writing and the restoring run through one queue shared by every bulk change
 * on the server, [AlteredBlockManager.bulkBlocksPerTick] blocks a tick, so a big blast burns out over a few
 * ticks or seconds instead of all at once.
 *
 * Positions are written in the order they were [add]ed (a crater: centre outwards) and restored in the
 * reverse order (rim to centre). The [target] decides each block's new data when its turn comes, from the
 * block as it is then; null leaves the block alone. Blocks with block-entity data (chests, signs, ...) are
 * never written by a bulk change, and positions in chunks that aren't loaded are skipped: it never loads
 * chunks. A restore leaves a block that no longer holds what the bulk change wrote (a player built there).
 *
 * Bulk blocks are ordinary altered blocks to everyone else: [AlteredBlockManager.isTracked], `getByBlock`,
 * `getBlocksInBox(ByCause)`, `undoByCause`, `previewUndoByCause` and the cause / name lookups see them, so the
 * paladin wrench and the repair tools mend them one by one. A block another feature changes (or that
 * another feature had changed) leaves the bulk change and becomes a per-block change, so stacking unwinds
 * as usual. Each chunk holds one record per bulk change (`sneakyutils:altered_bulk_<id>`), rewritten at the
 * end of each tick it changed; after a crash it is restored when the chunk loads.
 *
 * Call everything on the server thread. Configure, [add], then [start]; [undo] restores early.
 */
class BulkChange internal constructor(
    private val store: BulkChanges,
    val internalName: String,
    val world: World,
    val causes: Set<String>,
) {
    enum class State { NEW, WRITING, WRITTEN, RESTORING, DONE }

    val id: UUID = UUID.randomUUID()

    var state = State.NEW
        private set

    /** Positions skipped while writing because their chunk wasn't loaded. */
    var skippedUnloaded = 0
        private set

    /** Blocks this bulk change holds right now (written and not yet restored, mended or handed on). */
    var held = 0
        private set

    private var target: Function<Block, BlockData?>? = null
    private var restoreAfterTicks = 0L
    internal var physics = false
        private set
    private val writtenListeners = ArrayList<Runnable>()
    private val restoredListeners = ArrayList<Runnable>()
    private var restoreTask: BukkitTask? = null

    // queued positions, packed world coordinates, in add order
    private var queue = LongArray(256)
    private var queued = 0
    private var next = 0

    // what was written, in write order: (part, entry)
    private var logPart = IntArray(256)
    private var logEntry = IntArray(256)
    private var logged = 0

    internal val parts = ArrayList<Part>()
    private val partByChunk = HashMap<Long, Part>()
    internal val palette = ArrayList<BlockData>()
    private val paletteIndex = HashMap<BlockData, Int>()

    /** Blocks written as per-block changes (they had other changes), undone with the bulk change. */
    internal val perBlock = ArrayList<AlteredBlockManager.Change>()

    /** The blocks written in one chunk: packed local position, palette index of before and after, held bit. */
    internal class Part(val bulk: BulkChange, val id: Int, val cx: Int, val cz: Int) {
        var pos = IntArray(64)
        var from = CharArray(64)
        var to = CharArray(64)
        val held = BitSet()
        var size = 0
        var heldCount = 0
        val index = IntIntMap()
        var dirty = false
        /** Entries below this are in the chunk's record segments already. */
        var flushedUpTo = 0
        /** Record segments written for this chunk (keys `<record key>_<n>`). */
        var segments = 0

        fun append(local: Int, f: Int, t: Int): Int {
            if (size == pos.size) {
                pos = pos.copyOf(size * 2); from = from.copyOf(size * 2); to = to.copyOf(size * 2)
            }
            pos[size] = local; from[size] = f.toChar(); to[size] = t.toChar()
            held.set(size)
            heldCount++
            index.put(local, size)
            dirty = true
            return size++
        }

        fun entryAt(local: Int): Int {
            val e = index.get(local)
            return if (e >= 0 && held.get(e)) e else -1
        }
    }

    // ------------------------------------------------------------------------------------------- configuration

    /** Decides each block's new data when its turn comes; null leaves the block. Required before [start]. */
    fun target(target: Function<Block, BlockData?>): BulkChange = apply {
        checkNew()
        this.target = target
    }

    /** Restores everything [ticks] server ticks after the last block is written. Without it, only [undo] restores. */
    fun restoreAfter(ticks: Long): BulkChange = apply {
        checkNew()
        require(ticks > 0) { "ticks must be positive" }
        restoreAfterTicks = ticks
    }

    /** Whether writes and restores update neighbours (default false). */
    fun applyPhysics(physics: Boolean): BulkChange = apply {
        checkNew()
        this.physics = physics
    }

    /** Runs once the last queued position has been written (or skipped). */
    fun onWritten(listener: Runnable): BulkChange = apply { writtenListeners += listener }

    /** Runs once nothing is held any more: restored, mended or handed on. */
    fun onRestored(listener: Runnable): BulkChange = apply { restoredListeners += listener }

    /** Queues a position. Allowed until writing has finished. */
    fun add(x: Int, y: Int, z: Int): BulkChange = apply {
        check(state == State.NEW || state == State.WRITING) { "Bulk change $internalName is already written" }
        if (queued == queue.size) queue = queue.copyOf(queued * 2)
        queue[queued++] = pack(x, y, z)
    }

    /** Starts writing through the shared queue. */
    fun start(): BulkChange = apply {
        checkNew()
        checkNotNull(target) { "Bulk change $internalName has no target" }
        state = State.WRITING
        store.started(this)
    }

    /** Stops writing (if it still is) and restores everything held, rim to centre, through the shared queue. */
    fun undo() {
        if (state == State.RESTORING || state == State.DONE) return
        restoreTask?.cancel()
        restoreTask = null
        next = queued
        if (state == State.NEW) {
            finish()
            return
        }
        state = State.RESTORING
        store.wake()
    }

    private fun checkNew() = check(state == State.NEW) { "Bulk change $internalName has already started" }

    // ----------------------------------------------------------------------------------------------- the work

    /** Writes or restores up to [max] blocks; how many it changed. */
    internal fun step(max: Int): Int = when (state) {
        State.WRITING -> writeStep(max)
        State.RESTORING -> restoreStep(max)
        else -> 0
    }

    private fun writeStep(max: Int): Int {
        val fn = target ?: return 0
        val mgr = store.manager
        var writes = 0
        var looked = 0
        // positions left alone (air, protected) cost a look but no write: bound the looks too
        while (next < queued && writes < max && looked < max * 4) {
            val p = queue[next++]
            looked++
            val x = unpackX(p); val y = unpackY(p); val z = unpackZ(p)
            if (y < world.minHeight || y >= world.maxHeight) continue
            if (!world.isChunkLoaded(x shr 4, z shr 4)) { skippedUnloaded++; continue }
            val block = world.getBlockAt(x, y, z)
            if (mgr.isBeingRestored(block)) continue
            val ownPart = partByChunk[chunkKey(x shr 4, z shr 4)]
            if (ownPart != null && ownPart.entryAt(local(x, y, z)) >= 0) continue
            if (block.getState(false) is TileState) continue
            val data = fn.apply(block) ?: continue
            if (mgr.hasPerBlockChanges(block) || store.slotAt(block) != null) {
                // already changed by something else: stack on it as a per-block change
                store.handOn(block)
                perBlock += mgr.apply(internalName, block, data, physics, causes)
                writes++
                continue
            }
            val from = block.blockData
            val f = paletteOf(from)
            val t = paletteOf(data)
            if (f < 0 || t < 0) continue
            block.setBlockData(data, physics)
            val part = partFor(x shr 4, z shr 4)
            val e = part.append(local(x, y, z), f, t)
            held++
            if (logged == logPart.size) { logPart = logPart.copyOf(logged * 2); logEntry = logEntry.copyOf(logged * 2) }
            logPart[logged] = part.id; logEntry[logged] = e; logged++
            writes++
        }
        if (next >= queued) written()
        return writes
    }

    private fun written() {
        state = State.WRITTEN
        queue = LongArray(0)
        queued = 0
        next = 0
        store.fire(writtenListeners, "written")
        if (held == 0 && perBlock.isEmpty()) {
            finish()
            return
        }
        if (restoreAfterTicks > 0) {
            restoreTask = Bukkit.getScheduler().runTaskLater(store.plugin, Runnable {
                restoreTask = null
                undo()
            }, restoreAfterTicks)
        }
    }

    private fun restoreStep(max: Int): Int {
        var writes = 0
        while (logged > 0 && writes < max) {
            logged--
            val part = parts[logPart[logged]]
            val e = logEntry[logged]
            if (!part.held.get(e)) continue
            if (!world.isChunkLoaded(part.cx, part.cz)) continue   // restored when the chunk loads
            restoreEntry(part, e)
            writes++
        }
        if (logged == 0) {
            if (perBlock.isNotEmpty()) {
                for (c in perBlock.asReversed()) c.undo(physics)
                perBlock.clear()
            }
            if (held == 0) finish()
        }
        return writes
    }

    /** Puts one held block back (unless something replaced what was written) and lets go of it. */
    internal fun restoreEntry(part: Part, e: Int) {
        val (x, y, z) = worldPos(part, e)
        val block = world.getBlockAt(x, y, z)
        store.manager.whileBulkRestoring(block) {
            if (block.blockData == palette[part.to[e].code]) block.setBlockData(palette[part.from[e].code], physics)
        }
        release(part, e)
    }

    /** Lets go of a held block without touching it. */
    internal fun release(part: Part, e: Int) {
        if (!part.held.get(e)) return
        part.held.clear(e)
        part.heldCount--
        held--
        // records only grow while writing; a released entry left in one is harmless (a crash restore only touches
        // a block that still holds what was written), and the chunk's segments go once it holds nothing
        if (part.heldCount == 0) {
            part.dirty = true
            store.dirty(part)
        }
        if (held == 0 && perBlock.isEmpty() && state == State.WRITTEN) {
            restoreTask?.cancel()
            restoreTask = null
            finish()
        } else if (held == 0 && state == State.RESTORING && logged == 0) finish()
    }

    /** A held block mended one by one (a wrench, undoByCause): back to what it was. */
    internal fun mend(part: Part, e: Int) = restoreEntry(part, e)

    /** Restores every held block in [part] now (its chunk just loaded, or the server is stopping). */
    internal fun restorePart(part: Part) {
        var e = part.held.nextSetBit(0)
        while (e >= 0) {
            restoreEntry(part, e)
            e = part.held.nextSetBit(e + 1)
        }
    }

    private fun finish() {
        if (state == State.DONE) return
        state = State.DONE
        for (part in parts) part.dirty = true
        store.finished(this)
        store.fire(restoredListeners, "restored")
    }

    // ------------------------------------------------------------------------------------------- bookkeeping

    private fun paletteOf(data: BlockData): Int {
        paletteIndex[data]?.let { return it }
        if (palette.size >= 0xFFFF) return -1
        val i = palette.size
        val copy = data.clone()
        palette += copy
        paletteIndex[copy] = i
        return i
    }

    private fun partFor(cx: Int, cz: Int): Part = partByChunk.getOrPut(chunkKey(cx, cz)) {
        Part(this, parts.size, cx, cz).also {
            parts += it
            store.indexPart(it)
        }
    }

    internal fun partAt(cx: Int, cz: Int): Part? = partByChunk[chunkKey(cx, cz)]

    internal fun worldPos(part: Part, e: Int): Triple<Int, Int, Int> {
        val l = part.pos[e]
        return Triple(part.cx * 16 + ((l shr 4) and 15), (l shr 8) - Y_OFFSET, part.cz * 16 + (l and 15))
    }

    internal fun recordKey(segment: Int): NamespacedKey = NamespacedKey(store.plugin, "$RECORD_PREFIX${id}_$segment")

    /**
     * Brings [part]'s chunk records up to date: the entries written since the last call go into a new segment
     * (append-only, so each tick costs only what it wrote), and every segment goes once the chunk holds nothing.
     */
    internal fun writeRecord(part: Part) {
        part.dirty = false
        if (!world.isChunkLoaded(part.cx, part.cz)) return
        val pdc = world.getChunkAt(part.cx, part.cz).persistentDataContainer
        if (part.heldCount == 0 || state == State.DONE) {
            for (n in 0 until part.segments) pdc.remove(recordKey(n))
            part.segments = 0
            part.flushedUpTo = part.size
            return
        }
        val count = (part.flushedUpTo until part.size).count { part.held.get(it) }
        if (count == 0) {
            part.flushedUpTo = part.size
            return
        }
        val pos = IntArray(count)
        val ft = IntArray(count)
        var k = 0
        for (e in part.flushedUpTo until part.size) {
            if (!part.held.get(e)) continue
            pos[k] = part.pos[e]
            ft[k] = (part.from[e].code shl 16) or part.to[e].code
            k++
        }
        part.flushedUpTo = part.size
        val key = recordKey(part.segments++)
        val record = pdc.adapterContext.newPersistentDataContainer()
        record.set(store.formatKey, PersistentDataType.INTEGER, RECORD_FORMAT)
        record.set(store.paletteKey, PersistentDataType.LIST.strings(), palette.map { it.asString })
        record.set(store.posKey, PersistentDataType.INTEGER_ARRAY, pos)
        record.set(store.ftKey, PersistentDataType.INTEGER_ARRAY, ft)
        pdc.set(key, PersistentDataType.TAG_CONTAINER, record)
    }

    internal companion object {
        const val RECORD_PREFIX = "altered_bulk_"
        const val RECORD_FORMAT = 1
        const val Y_OFFSET = 4096

        fun local(x: Int, y: Int, z: Int): Int = ((y + Y_OFFSET) shl 8) or ((x and 15) shl 4) or (z and 15)

        fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        fun unpackX(p: Long): Int = (p shr 38).toInt()
        fun unpackZ(p: Long): Int = ((p shl 26) shr 38).toInt()
        fun unpackY(p: Long): Int = ((p shl 52) shr 52).toInt()
    }
}

/** Every live [BulkChange], the shared queue that runs them and the chunk index the manager's lookups use. */
internal class BulkChanges(val manager: AlteredBlockManager, val plugin: JavaPlugin) {
    val formatKey = NamespacedKey(plugin, "altered_bulk_format")
    val paletteKey = NamespacedKey(plugin, "altered_bulk_palette")
    val posKey = NamespacedKey(plugin, "altered_bulk_pos")
    val ftKey = NamespacedKey(plugin, "altered_bulk_ft")

    private val live = LinkedHashMap<UUID, BulkChange>()
    private val byChunk = HashMap<UUID, HashMap<Long, MutableList<BulkChange.Part>>>()
    private val dirtyParts = LinkedHashSet<BulkChange.Part>()
    private var task: BukkitTask? = null

    /** Budget for all bulk changes together, read when the queue starts. */
    var blocksPerTick = 5000

    val all: Collection<BulkChange> get() = live.values

    fun create(internalName: String, world: World, causes: Set<String>): BulkChange {
        AlteredBlockCauses.requireValid(causes)
        return BulkChange(this, internalName, world, causes.toSet())
    }

    fun started(bulk: BulkChange) {
        live[bulk.id] = bulk
        wake()
    }

    fun wake() {
        if (task != null) return
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun isLive(id: UUID) = live.containsKey(id)

    /** One tick of the shared queue: the budget split between the bulk changes at work, then the records. */
    fun tick() {
        val working = live.values.filter { it.state == BulkChange.State.WRITING || it.state == BulkChange.State.RESTORING }
        var budget = blocksPerTick
        var left = working.size
        for (bulk in working) {
            val share = budget / left--
            budget -= try {
                bulk.step(share)
            } catch (e: RuntimeException) {
                plugin.logger.severe("Bulk change ${bulk.internalName} failed this tick: ${e.message}")
                0
            }
        }
        flush()
        if (live.isEmpty() && dirtyParts.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    fun dirty(part: BulkChange.Part) {
        dirtyParts += part
        wake()
    }

    fun flush() {
        for (bulk in live.values) for (part in bulk.parts) if (part.dirty) dirtyParts += part
        for (part in dirtyParts) {
            try {
                part.bulk.writeRecord(part)
            } catch (e: RuntimeException) {
                plugin.logger.severe("Couldn't write the record of bulk change ${part.bulk.internalName}: ${e.message}")
            }
        }
        dirtyParts.clear()
    }

    fun indexPart(part: BulkChange.Part) {
        byChunk.getOrPut(part.bulk.world.uid) { HashMap() }
            .getOrPut(BulkChange.chunkKey(part.cx, part.cz)) { ArrayList() } += part
    }

    fun finished(bulk: BulkChange) {
        live.remove(bulk.id)
        for (part in bulk.parts) {
            dirtyParts += part
            val chunks = byChunk[bulk.world.uid] ?: continue
            val key = BulkChange.chunkKey(part.cx, part.cz)
            chunks[key]?.let { it.remove(part); if (it.isEmpty()) chunks.remove(key) }
        }
        flush()
    }

    class Slot(val bulk: BulkChange, val part: BulkChange.Part, val entry: Int)

    /** The bulk change holding [block], if any. */
    fun slotAt(block: Block): Slot? {
        val parts = byChunk[block.world.uid]?.get(BulkChange.chunkKey(block.x shr 4, block.z shr 4)) ?: return null
        val local = BulkChange.local(block.x, block.y, block.z)
        for (part in parts) {
            val e = part.entryAt(local)
            if (e >= 0) return Slot(part.bulk, part, e)
        }
        return null
    }

    /**
     * [block] leaves its bulk change and becomes a per-block change under the bulk change's name with what
     * was there before it, so a change stacked on it later unwinds to the original.
     */
    fun handOn(block: Block) {
        val slot = slotAt(block) ?: return
        val bulk = slot.bulk
        val change = AlteredBlockManager.Change(
            bulk.internalName, block, bulk.palette[slot.part.from[slot.entry].code].clone(), block.state, bulk.causes,
        )
        bulk.perBlock += change
        manager.addHandedOn(change)
        bulk.release(slot.part, slot.entry)
    }

    /** Held blocks in [world]'s chunks cx/cz ranges whose bulk change matches; for box queries. */
    fun forEachHeldIn(
        world: World, minCx: Int, maxCx: Int, minCz: Int, maxCz: Int,
        matches: (BulkChange) -> Boolean, action: (BulkChange, BulkChange.Part, Int) -> Unit,
    ) {
        val chunks = byChunk[world.uid] ?: return
        for ((key, parts) in chunks) {
            val cx = (key shr 32).toInt()
            val cz = key.toInt()
            if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue
            for (part in parts) {
                if (!matches(part.bulk)) continue
                var e = part.held.nextSetBit(0)
                while (e >= 0) {
                    action(part.bulk, part, e)
                    e = part.held.nextSetBit(e + 1)
                }
            }
        }
    }

    /** A chunk loaded: live bulk changes restoring finish there; records no live bulk change owns are restored. */
    fun chunkLoaded(chunk: Chunk) {
        byChunk[chunk.world.uid]?.get(BulkChange.chunkKey(chunk.x, chunk.z))?.toList()?.forEach { part ->
            if (part.bulk.state == BulkChange.State.RESTORING) part.bulk.restorePart(part)
        }
        val pdc = chunk.persistentDataContainer
        val keys = pdc.keys.filter { it.namespace == formatKey.namespace && it.key.startsWith(BulkChange.RECORD_PREFIX) }
        for (key in keys) {
            val id = try { UUID.fromString(key.key.removePrefix(BulkChange.RECORD_PREFIX).substringBeforeLast('_')) } catch (_: IllegalArgumentException) { null }
            if (id != null && isLive(id)) continue
            try {
                restoreRecord(chunk, pdc, key)
            } catch (e: RuntimeException) {
                plugin.logger.severe("Failed to restore bulk record ${key.key} in chunk ${chunk.x}, ${chunk.z}: ${e.message}")
            }
            pdc.remove(key)
        }
    }

    private fun restoreRecord(chunk: Chunk, pdc: PersistentDataContainer, key: NamespacedKey) {
        val record = try { pdc.get(key, PersistentDataType.TAG_CONTAINER) } catch (_: IllegalArgumentException) { null } ?: return
        val palette = record.get(paletteKey, PersistentDataType.LIST.strings()) ?: return
        val pos = record.get(posKey, PersistentDataType.INTEGER_ARRAY) ?: return
        val ft = record.get(ftKey, PersistentDataType.INTEGER_ARRAY) ?: return
        val data = palette.map { s -> try { Bukkit.createBlockData(s) } catch (_: IllegalArgumentException) { null } }
        var restored = 0
        for (k in pos.indices.reversed()) {
            if (k >= ft.size) continue
            val l = pos[k]
            val from = data.getOrNull(ft[k] ushr 16) ?: continue
            val to = data.getOrNull(ft[k] and 0xFFFF) ?: continue
            val block = chunk.getBlock((l shr 4) and 15, (l shr 8) - BulkChange.Y_OFFSET, l and 15)
            if (manager.isTracked(block)) continue
            if (block.blockData == to) {
                block.setBlockData(from, false)
                restored++
            }
        }
        plugin.logger.info("Restored $restored block(s) of a bulk change left by a crash in chunk ${chunk.x}, ${chunk.z}.")
    }

    /** On disable: held blocks in loaded chunks go back now; the rest are left to their records. */
    fun restoreLoaded() {
        for (bulk in live.values.toList()) {
            for (part in bulk.parts) if (bulk.world.isChunkLoaded(part.cx, part.cz)) bulk.restorePart(part)
            for (c in bulk.perBlock.asReversed()) c.undo(bulk.physics)
            bulk.perBlock.clear()
        }
        flush()
        task?.cancel()
        task = null
    }

    fun fire(listeners: List<Runnable>, what: String) {
        for (l in listeners) {
            try {
                l.run()
            } catch (e: RuntimeException) {
                plugin.logger.severe("A bulk change's $what listener failed: ${e.message}")
            }
        }
    }
}

/** A small open-addressing int to int map (no boxing); -1 means absent. Keys are non-negative. */
internal class IntIntMap {
    private var keys = IntArray(16) { -1 }
    private var vals = IntArray(16)
    private var size = 0

    fun put(k: Int, v: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        var i = slot(k, keys.size)
        while (keys[i] != -1 && keys[i] != k) i = (i + 1) and (keys.size - 1)
        if (keys[i] == -1) size++
        keys[i] = k
        vals[i] = v
    }

    fun get(k: Int): Int {
        var i = slot(k, keys.size)
        while (keys[i] != -1) {
            if (keys[i] == k) return vals[i]
            i = (i + 1) and (keys.size - 1)
        }
        return -1
    }

    private fun grow() {
        val ok = keys
        val ov = vals
        keys = IntArray(ok.size * 2) { -1 }
        vals = IntArray(ok.size * 2)
        size = 0
        for (j in ok.indices) if (ok[j] != -1) put(ok[j], ov[j])
    }

    private fun slot(k: Int, n: Int) = (k * -0x61c88647 ushr 7) and (n - 1)
}
