package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.OccupancyOptions
import java.util.UUID

/**
 * One registered area as the engine sees it. [link] points back to the subscription that owns it (null in pure
 * engine tests).
 *
 * [occupancy] is set for an occupancy ([com.sneakyutils.triggers.Triggers.occupancy]): its enters and exits are queued
 * with an occupancy cause, members who die stay ([keepDead]) and members who become spectators leave
 * ([dropsSpectators]). Its [area] may be replaced by [MovementCore.moveArea]; other registrations keep theirs.
 */
internal class AreaReg(
    area: Area,
    @JvmField val options: AreaOptions,
    @JvmField val occupancy: OccupancyOptions? = null,
) {
    /** The indexed area. Changed only by [MovementCore.moveArea], together with the index. */
    @JvmField var area: Area = area

    /** Occupancy members stay through death until they respawn elsewhere ([OccupancyOptions.countDead]). */
    @JvmField val keepDead: Boolean = occupancy != null && occupancy.countDead

    /** Occupancy: a member who becomes a spectator leaves (unless [OccupancyOptions.includeSpectators]). */
    @JvmField val dropsSpectators: Boolean = occupancy != null && !occupancy.includeSpectators

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
        reg.seq = ++added
        val w = worlds.getOrPut(a.worldId) { WorldAreas(a.worldId) }
        for (cx in (a.minX shr 4)..(a.maxX shr 4)) {
            for (cz in (a.minZ shr 4)..(a.maxZ shr 4)) list(w, Geometry.chunkKey(cx, cz), reg)
        }
        w.areaCount++
        areaCount++
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
        if (isShapedOccupancy(reg, a)) shapedOccupancies++
        version++
    }

    /**
     * Indexed occupancies whose area is a shape (sphere, cylinder, custom), all worlds. Changed only where a
     * registration enters, leaves or moves in the index ([add], [remove], [move], [clear]), so it can't drift. While it
     * is 0 the engine and service skip everything that exists only for such occupancies (same-block re-tests, exact
     * position refreshes): a server without them does what it did before occupancies existed.
     */
    var shapedOccupancies: Int = 0
        private set

    private fun isShapedOccupancy(reg: AreaReg, area: Area): Boolean = reg.occupancy != null && area.isCustom

    /** Removes [reg]; does nothing if it isn't indexed. */
    fun remove(reg: AreaReg) {
        if (!reg.active) return
        reg.active = false
        val a = reg.area
        if (isShapedOccupancy(reg, a)) shapedOccupancies--
        val w = worlds[a.worldId] ?: return
        for (cx in (a.minX shr 4)..(a.maxX shr 4)) {
            for (cz in (a.minZ shr 4)..(a.maxZ shr 4)) unlist(w, Geometry.chunkKey(cx, cz), reg)
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

    /**
     * Re-anchors an indexed [reg] on [to] (an occupancy's [MovementCore.moveArea]) and sets [AreaReg.area]. It keeps
     * its registration order. Within one world only the chunks the area starts or stops touching change (none for a
     * move inside the same chunks, and then [version] stays: every cached chunk array is still current); across worlds
     * it leaves the old world's index and joins the new one's. Pass-through areas can't move (their union box would
     * need recomputing).
     */
    fun move(reg: AreaReg, to: Area) {
        check(reg.active) { "$reg is not indexed" }
        require(!reg.options.passThrough) { "pass-through areas can't move" }
        val from = reg.area
        reg.area = to
        if (isShapedOccupancy(reg, from)) shapedOccupancies--
        if (isShapedOccupancy(reg, to)) shapedOccupancies++
        val fx0 = from.minX shr 4
        val fx1 = from.maxX shr 4
        val fz0 = from.minZ shr 4
        val fz1 = from.maxZ shr 4
        val tx0 = to.minX shr 4
        val tx1 = to.maxX shr 4
        val tz0 = to.minZ shr 4
        val tz1 = to.maxZ shr 4
        if (from.worldId == to.worldId) {
            if (fx0 == tx0 && fx1 == tx1 && fz0 == tz0 && fz1 == tz1) return // same chunks: the listings stay
            val w = worlds[to.worldId]!!
            for (cx in fx0..fx1) for (cz in fz0..fz1) {
                if (cx !in tx0..tx1 || cz !in tz0..tz1) unlist(w, Geometry.chunkKey(cx, cz), reg)
            }
            for (cx in tx0..tx1) for (cz in tz0..tz1) {
                if (cx !in fx0..fx1 || cz !in fz0..fz1) list(w, Geometry.chunkKey(cx, cz), reg)
            }
        } else {
            val old = worlds[from.worldId]!!
            for (cx in fx0..fx1) for (cz in fz0..fz1) unlist(old, Geometry.chunkKey(cx, cz), reg)
            if (--old.areaCount == 0) worlds.remove(from.worldId)
            val w = worlds.getOrPut(to.worldId) { WorldAreas(to.worldId) }
            for (cx in tx0..tx1) for (cz in tz0..tz1) list(w, Geometry.chunkKey(cx, cz), reg)
            w.areaCount++
        }
        version++
    }

    /** Lists [reg] in one chunk, keeping the chunk's array in registration order (copy-on-write). */
    private fun list(w: WorldAreas, key: Long, reg: AreaReg) {
        val old = w.chunks[key]
        if (old == null) {
            w.chunks.put(key, arrayOf(reg))
            if (maxChunkListing == 0) maxChunkListing = 1
        } else {
            // Usually the newest, so it goes last; a moved area may go further in.
            var at = old.size
            while (at > 0 && old[at - 1].seq > reg.seq) at--
            val grown = arrayOfNulls<AreaReg>(old.size + 1)
            System.arraycopy(old, 0, grown, 0, at)
            grown[at] = reg
            System.arraycopy(old, at, grown, at + 1, old.size - at)
            @Suppress("UNCHECKED_CAST")
            w.chunks.put(key, grown as Array<AreaReg>)
            if (grown.size > maxChunkListing) maxChunkListing = grown.size
        }
        chunkEntries++
    }

    /** Removes [reg] from one chunk's listing, if it is there. */
    private fun unlist(w: WorldAreas, key: Long, reg: AreaReg) {
        val old = w.chunks[key] ?: return
        val i = old.indexOf(reg)
        if (i < 0) return
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
        shapedOccupancies = 0
        areaCount = 0
        chunkEntries = 0
        version++
    }
}
