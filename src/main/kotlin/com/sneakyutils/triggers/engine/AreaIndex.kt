package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import java.util.UUID

/**
 * One registered area as the engine sees it. [link] points back to the subscription that owns it (null in pure
 * engine tests).
 */
internal class AreaReg(@JvmField val area: Area, @JvmField val options: AreaOptions) {
    @JvmField var link: Any? = null

    /** True while in the index; the engine skips inactive registrations still held in caches or memberships. */
    @JvmField var active: Boolean = false

    /** Set when the owner or SneakyUtils disabled: queued enters and exits for it are dropped, not delivered. */
    @JvmField var silenced: Boolean = false

    /** Evaluation stamp: the engine tests each candidate once per evaluation (see [MovementCore]). */
    @JvmField var visitStamp: Long = 0

    /** Registration order: set by [AreaIndex.add] from a counter that only grows. */
    @JvmField var seq: Long = 0

    /** Players currently counted inside (announced or silent), so closing can skip the member scan when 0. */
    @JvmField var members: Int = 0

    override fun toString(): String = "AreaReg($area)"
}

/**
 * The areas of one world, by chunk. Arrays are replaced on change, never modified (copy-on-write). Exists only while
 * the world has at least one area: [AreaIndex] drops it with the last one.
 */
internal class WorldAreas(val worldId: UUID) {
    /** chunk key -> areas touching that chunk. A missing key means no areas. */
    val chunks = LongObjectMap<Array<AreaReg>>(64)

    /** Registered areas in this world, and how many want pass-through. */
    var areaCount = 0
        internal set
    var passThroughCount = 0
        internal set

    // Union of the block bounds of this world's pass-through areas (valid while passThroughCount > 0). Grows on add;
    // recomputed from the remaining areas on remove.
    var ptMinX = 0; internal set
    var ptMinZ = 0; internal set
    var ptMaxX = 0; internal set
    var ptMaxZ = 0; internal set

    /** The areas listed in the chunk containing block (x, z), or null. */
    fun areasAtBlock(x: Int, z: Int): Array<AreaReg>? = chunks[Geometry.chunkKey(x shr 4, z shr 4)]
}

/**
 * Areas indexed by world, then chunk: `world UUID -> chunk key -> AreaReg[]`. An area is listed in every chunk its
 * box touches (at most 64 x 64 chunks, enforced by [Area]).
 *
 * [version] increases on every add and remove. Each player state remembers the version its cached chunk array was
 * read at and re-reads when it differs, so a change never scans the online players.
 */
internal class AreaIndex {
    private val worlds = HashMap<UUID, WorldAreas>()

    /** Bumped on every change. A long, so it never wraps back to a value a player cached. */
    var version: Long = 0
        private set

    /** Areas ever added: the source of [AreaReg.seq]. */
    private var added = 0L

    /** Registered areas, all worlds. */
    var areaCount: Int = 0
        private set

    /** The most areas one chunk has listed since start (a high-water mark, for the dense-chunk warning). */
    var maxChunkListing: Int = 0
        private set

    /** Chunk entries, all worlds (an area spanning n chunks counts n). */
    var chunkEntries: Int = 0
        private set

    /** The world's index, or null when it has no areas. */
    fun world(worldId: UUID): WorldAreas? = worlds[worldId]

    fun add(reg: AreaReg) {
        check(!reg.active) { "$reg is already indexed" }
        val a = reg.area
        val w = worlds.getOrPut(a.worldId) { WorldAreas(a.worldId) }
        for (cx in (a.minX shr 4)..(a.maxX shr 4)) {
            for (cz in (a.minZ shr 4)..(a.maxZ shr 4)) {
                val key = Geometry.chunkKey(cx, cz)
                val old = w.chunks[key]
                if (old == null) {
                    w.chunks.put(key, arrayOf(reg))
                    if (maxChunkListing == 0) maxChunkListing = 1
                } else {
                    val grown = old.copyOf(old.size + 1)
                    grown[old.size] = reg
                    @Suppress("UNCHECKED_CAST")
                    w.chunks.put(key, grown as Array<AreaReg>)
                    if (grown.size > maxChunkListing) maxChunkListing = grown.size
                }
                chunkEntries++
            }
        }
        w.areaCount++
        areaCount++
        reg.seq = ++added
        if (reg.options.passThrough) {
            if (w.passThroughCount == 0) {
                w.ptMinX = a.minX; w.ptMinZ = a.minZ; w.ptMaxX = a.maxX; w.ptMaxZ = a.maxZ
            } else {
                w.ptMinX = minOf(w.ptMinX, a.minX); w.ptMinZ = minOf(w.ptMinZ, a.minZ)
                w.ptMaxX = maxOf(w.ptMaxX, a.maxX); w.ptMaxZ = maxOf(w.ptMaxZ, a.maxZ)
            }
            w.passThroughCount++
        }
        reg.active = true
        version++
    }

    /** Removes [reg]; does nothing if it isn't indexed. */
    fun remove(reg: AreaReg) {
        if (!reg.active) return
        reg.active = false
        val a = reg.area
        val w = worlds[a.worldId] ?: return
        for (cx in (a.minX shr 4)..(a.maxX shr 4)) {
            for (cz in (a.minZ shr 4)..(a.maxZ shr 4)) {
                val key = Geometry.chunkKey(cx, cz)
                val old = w.chunks[key] ?: continue
                val i = old.indexOf(reg)
                if (i < 0) continue
                if (old.size == 1) {
                    w.chunks.remove(key)
                } else {
                    val shrunk = arrayOfNulls<AreaReg>(old.size - 1)
                    System.arraycopy(old, 0, shrunk, 0, i)
                    System.arraycopy(old, i + 1, shrunk, i, old.size - i - 1)
                    @Suppress("UNCHECKED_CAST")
                    w.chunks.put(key, shrunk as Array<AreaReg>)
                }
                chunkEntries--
            }
        }
        w.areaCount--
        areaCount--
        if (w.areaCount == 0) {
            // Drop the world's index (instance worlds come and go). Player caches holding it re-read on the version
            // change below and get null.
            worlds.remove(a.worldId)
        } else if (reg.options.passThrough) {
            w.passThroughCount--
            if (w.passThroughCount > 0) recomputePassThroughBounds(w)
        }
        version++
    }

    /** The registrations in one world, each once. Allocates. */
    fun regsInWorld(worldId: UUID): List<AreaReg> {
        val w = worlds[worldId] ?: return emptyList()
        val seen = java.util.IdentityHashMap<AreaReg, Boolean>()
        val out = ArrayList<AreaReg>()
        w.chunks.forEachEntry { _, regs -> for (r in regs) if (seen.put(r, true) == null) out += r }
        return out
    }

    /** The chunk listing the most areas now: its world, chunk key and count; null without areas. Scans. */
    fun densestChunk(): Triple<UUID, Long, Int>? {
        var best: Triple<UUID, Long, Int>? = null
        for (w in worlds.values) {
            w.chunks.forEachEntry { key, regs ->
                val b = best
                if (b == null || regs.size > b.third) best = Triple(w.worldId, key, regs.size)
            }
        }
        return best
    }

    /** Worlds with an index; for tests. */
    internal val worldCount: Int get() = worlds.size

    private fun recomputePassThroughBounds(w: WorldAreas) {
        var first = true
        val seen = java.util.IdentityHashMap<AreaReg, Boolean>()
        w.chunks.forEachEntry { _, regs ->
            for (r in regs) {
                if (!r.options.passThrough || seen.put(r, true) != null) continue
                val a = r.area
                if (first) {
                    w.ptMinX = a.minX; w.ptMinZ = a.minZ; w.ptMaxX = a.maxX; w.ptMaxZ = a.maxZ
                    first = false
                } else {
                    w.ptMinX = minOf(w.ptMinX, a.minX); w.ptMinZ = minOf(w.ptMinZ, a.minZ)
                    w.ptMaxX = maxOf(w.ptMaxX, a.maxX); w.ptMaxZ = maxOf(w.ptMaxZ, a.maxZ)
                }
            }
        }
    }

    /** Every indexed registration once, for stats and shutdown. Allocates. */
    fun allRegs(): List<AreaReg> {
        val seen = java.util.IdentityHashMap<AreaReg, Boolean>()
        val out = ArrayList<AreaReg>()
        for (w in worlds.values) w.chunks.forEachEntry { _, regs -> for (r in regs) if (seen.put(r, true) == null) out += r }
        return out
    }

    fun clear() {
        for (w in worlds.values) w.chunks.forEachEntry { _, regs -> for (r in regs) r.active = false }
        worlds.clear()
        areaCount = 0
        chunkEntries = 0
        version++
    }
}
