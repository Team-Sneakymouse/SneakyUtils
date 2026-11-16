package com.sneakyutils.triggers.engine

import kotlin.math.abs
import kotlin.math.floor

/**
 * Pure geometry for the movement engine: chunk keys, the chunk walk along a move, and the segment-box test.
 * No Bukkit types, no allocation.
 */
internal object Geometry {

    /**
     * The shortest crossing [segmentHitsBox] counts, as a fraction of the move (at most 64 blocks: under a ten
     * millionth of a block). Far above the rounding of its divisions (about 1e-16), far below any real crossing.
     */
    const val MIN_CROSSING = 1.0e-9

    /** Chunk key: chunk x in the high 32 bits, chunk z in the low 32 bits. Exact for every int pair. */
    @JvmStatic
    fun chunkKey(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    @JvmStatic
    fun chunkX(key: Long): Int = (key shr 32).toInt()

    @JvmStatic
    fun chunkZ(key: Long): Int = key.toInt()

    /** `floor(v)` as an int. Positions are finite and inside the world border, far inside int range. */
    @JvmStatic
    fun blockOf(v: Double): Int = floor(v).toInt()

    /**
     * Writes into [out] the keys of the chunks a horizontal move from (x0, z0) to (x1, z1) passes through, in path
     * order, starting with the start chunk and ending with the end chunk. Returns how many were written (at least 1).
     *
     * It is a 2-D DDA over the 16-block chunk grid (Amanatides and Woo). Where the path crosses a chunk corner
     * exactly, both side chunks are listed too, so the result is a superset of the chunks the path touches; the
     * caller's exact box test decides. At most `out.size` keys are written: when the cap is reached the end chunk
     * replaces the last slot, so the end chunk is always included. The caller sizes [out] for its segment limit
     * (a 64-block move crosses at most 11 chunk cells, plus corner extras).
     */
    @JvmStatic
    fun segmentChunks(x0: Double, z0: Double, x1: Double, z1: Double, out: LongArray): Int {
        val max = out.size
        require(max >= 2) { "out must hold at least 2 keys" }
        var cx = blockOf(x0) shr 4
        var cz = blockOf(z0) shr 4
        val ex = blockOf(x1) shr 4
        val ez = blockOf(z1) shr 4
        var n = 0
        out[n++] = chunkKey(cx, cz)
        if (cx == ex && cz == ez) return n

        val dx = x1 - x0
        val dz = z1 - z0
        val sx = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val sz = if (dz > 0) 1 else if (dz < 0) -1 else 0
        // t (0..1 along the move) of the next x and z chunk boundary, and the t between boundaries.
        val inf = Double.POSITIVE_INFINITY
        val tDeltaX = if (sx != 0) 16.0 / abs(dx) else inf
        val tDeltaZ = if (sz != 0) 16.0 / abs(dz) else inf
        var tMaxX = when {
            sx > 0 -> ((cx + 1) * 16.0 - x0) / dx
            sx < 0 -> (cx * 16.0 - x0) / dx
            else -> inf
        }
        var tMaxZ = when {
            sz > 0 -> ((cz + 1) * 16.0 - z0) / dz
            sz < 0 -> (cz * 16.0 - z0) / dz
            else -> inf
        }

        while (n < max) {
            if (cx == ex && cz == ez) return n
            if (tMaxX < tMaxZ) {
                if (tMaxX > 1.0) break // rounding: the next boundary is past the end
                cx += sx
                tMaxX += tDeltaX
            } else if (tMaxZ < tMaxX) {
                if (tMaxZ > 1.0) break
                cz += sz
                tMaxZ += tDeltaZ
            } else {
                // Exact corner: list both side chunks, then step diagonally.
                if (tMaxX > 1.0) break
                if (n < max) out[n++] = chunkKey(cx + sx, cz)
                if (n < max) out[n++] = chunkKey(cx, cz + sz)
                cx += sx
                cz += sz
                tMaxX += tDeltaX
                tMaxZ += tDeltaZ
            }
            if (n < max) out[n++] = chunkKey(cx, cz)
        }
        // Capped or stopped by rounding: make sure the end chunk is listed.
        val endKey = chunkKey(ex, ez)
        if (out[n - 1] != endKey) {
            if (n < max) out[n++] = endKey else out[max - 1] = endKey
        }
        return n
    }

    /**
     * Whether the move from (x0, y0, z0) to (x1, y1, z1) passes through the inclusive block box
     * [minX..maxX] x [minY..maxY] x [minZ..maxZ] for a positive length.
     *
     * The box is the half-open continuous region `[min, max + 1)` on each axis, the same rule as block membership
     * (`floor(p)` in `[min, max]`). Slab method: intersect the move's parameter range [0, 1] with each axis' slab;
     * the move passes through when the remaining range is longer than [MIN_CROSSING] (a positive length, less the
     * rounding of the divisions). A move that only touches an edge or a corner, or runs along the box's far face, does
     * not count, even when the path's direction isn't exactly representable. A zero-length move counts when its point
     * is inside.
     *
     * When [range] is given and the result is true, `range[0]` and `range[1]` receive the entry and exit parameter
     * (0..1 along the move), for sampling a custom shape inside the box.
     */
    @JvmStatic
    fun segmentHitsBox(
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        minX: Int, minY: Int, minZ: Int,
        maxX: Int, maxY: Int, maxZ: Int,
        range: DoubleArray? = null,
    ): Boolean {
        var tMin = 0.0
        var tMax = 1.0

        val dx = x1 - x0
        if (dx == 0.0) {
            if (x0 < minX || x0 >= maxX + 1.0) return false
        } else {
            var a = (minX - x0) / dx
            var b = (maxX + 1.0 - x0) / dx
            if (a > b) { val t = a; a = b; b = t }
            if (a > tMin) tMin = a
            if (b < tMax) tMax = b
            if (tMin > tMax) return false
        }

        val dy = y1 - y0
        if (dy == 0.0) {
            if (y0 < minY || y0 >= maxY + 1.0) return false
        } else {
            var a = (minY - y0) / dy
            var b = (maxY + 1.0 - y0) / dy
            if (a > b) { val t = a; a = b; b = t }
            if (a > tMin) tMin = a
            if (b < tMax) tMax = b
            if (tMin > tMax) return false
        }

        val dz = z1 - z0
        if (dz == 0.0) {
            if (z0 < minZ || z0 >= maxZ + 1.0) return false
        } else {
            var a = (minZ - z0) / dz
            var b = (maxZ + 1.0 - z0) / dz
            if (a > b) { val t = a; a = b; b = t }
            if (a > tMin) tMin = a
            if (b < tMax) tMax = b
            if (tMin > tMax) return false
        }

        val moving = dx != 0.0 || dy != 0.0 || dz != 0.0
        val hit = if (moving) tMax - tMin > MIN_CROSSING else true
        if (hit && range != null) {
            range[0] = tMin
            range[1] = tMax
        }
        return hit
    }
}
