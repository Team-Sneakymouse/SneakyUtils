package com.sneakyutils.triggers

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.util.BoundingBox
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A region for [Triggers.area]: a box of whole blocks, or a [custom] shape inside such a box.
 *
 * Bounds are inclusive block coordinates; the constructors accept the corners in any order. The world is held by
 * UUID, so an area never keeps a world loaded and survives the world unloading and loading again.
 *
 * An area may span at most [MAX_CHUNK_SPAN] x [MAX_CHUNK_SPAN] chunks: the trigger engine lists it in every chunk it
 * touches. Bigger regions are rejected with [IllegalArgumentException]; split them, or use a coarser design.
 *
 * Areas compare by identity: each [Triggers.area] registration is separate even for equal bounds.
 */
class Area private constructor(
    val worldId: UUID,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    private val predicate: AreaPredicate?,
) {
    init {
        val spanX = (maxX shr 4) - (minX shr 4) + 1
        val spanZ = (maxZ shr 4) - (minZ shr 4) + 1
        require(spanX <= MAX_CHUNK_SPAN && spanZ <= MAX_CHUNK_SPAN) {
            "Area spans $spanX x $spanZ chunks; the limit is $MAX_CHUNK_SPAN x $MAX_CHUNK_SPAN. " +
                "Split it into smaller areas or use a coarser design."
        }
    }

    /** True for [custom] areas, whose predicate refines the box. */
    val isCustom: Boolean get() = predicate != null

    /** The world, or null when it isn't loaded. */
    val world: World? get() = Bukkit.getWorld(worldId)

    /** Whether the block is inside the box (custom predicates are not applied). */
    fun boxContains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    /**
     * Whether the exact position is inside the area: its block is in the box and, for a custom area, the predicate
     * accepts it. Runs the consumer's predicate, so the engine calls it inside its error guard.
     */
    fun contains(x: Double, y: Double, z: Double): Boolean {
        if (!boxContains(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())) return false
        return predicate == null || predicate.test(x, y, z)
    }

    /** The custom predicate only (true for a box). The engine applies it after its own box check. */
    internal fun predicateAccepts(x: Double, y: Double, z: Double): Boolean = predicate == null || predicate.test(x, y, z)

    override fun toString(): String =
        "Area(${if (isCustom) "custom" else "box"} $worldId [$minX,$minY,$minZ]..[$maxX,$maxY,$maxZ])"

    companion object {
        /** The largest span in chunks, per horizontal axis. */
        const val MAX_CHUNK_SPAN = 64

        /** A box of whole blocks with inclusive corners, in any order. */
        @JvmStatic
        fun box(world: World, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Area =
            box(world.uid, x1, y1, z1, x2, y2, z2)

        /** A box of whole blocks in the world with this UUID, with inclusive corners, in any order. */
        @JvmStatic
        fun box(worldId: UUID, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Area =
            Area(worldId, min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2), null)

        /**
         * A custom shape (for example a WorldGuard polygon) inside an inclusive block box. The engine indexes the box,
         * then asks [predicate] with the player's exact position. The predicate must be cheap and side-effect free:
         * it runs on the server thread during movement, possibly several times per step.
         */
        @JvmStatic
        fun custom(world: World, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, predicate: AreaPredicate): Area =
            custom(world.uid, x1, y1, z1, x2, y2, z2, predicate)

        @JvmStatic
        fun custom(worldId: UUID, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, predicate: AreaPredicate): Area =
            Area(worldId, min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2), predicate)

        /**
         * A custom shape bounded by [bounds] (exact coordinates, as WorldGuard and Bukkit give them). The block box
         * is every block the bounds overlap: from `floor(min)` to `ceil(max) - 1` on each axis.
         */
        @JvmStatic
        fun custom(world: World, bounds: BoundingBox, predicate: AreaPredicate): Area {
            val minX = floor(bounds.minX).toInt()
            val minY = floor(bounds.minY).toInt()
            val minZ = floor(bounds.minZ).toInt()
            return Area(
                world.uid, minX, minY, minZ,
                max(minX, ceil(bounds.maxX).toInt() - 1),
                max(minY, ceil(bounds.maxY).toInt() - 1),
                max(minZ, ceil(bounds.maxZ).toInt() - 1),
                predicate,
            )
        }
    }
}

/** The exact shape of a [Area.custom] area: whether a position (in block coordinates) is inside. */
fun interface AreaPredicate {
    fun test(x: Double, y: Double, z: Double): Boolean
}
