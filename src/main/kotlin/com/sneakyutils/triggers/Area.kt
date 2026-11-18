package com.sneakyutils.triggers

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A region for [Triggers.area] and [Triggers.occupancy]: a box of whole blocks, or a shape inside such a box
 * ([custom], [sphere], [cylinder]).
 *
 * Bounds are inclusive block coordinates; the constructors accept the corners in any order. The world is held by
 * UUID, so an area never keeps a world loaded and survives the world unloading and loading again.
 *
 * An area may span at most [MAX_CHUNK_SPAN] x [MAX_CHUNK_SPAN] chunks: the trigger engine lists it in every chunk it
 * touches. Bigger regions are rejected with [IllegalArgumentException]; split them, or use a coarser design.
 *
 * Areas compare by identity: each [Triggers.area] registration is separate even for equal bounds. An area is
 * immutable: [translate] returns a moved copy.
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
    /** What [toString] calls the shape: box, custom, sphere or cylinder. */
    private val shape: String = if (predicate == null) "box" else "custom",
    // Whole-block offset from translate. A custom predicate is asked with the position moved back by this much, so a
    // translated shape keeps its original predicate instead of wrapping it once per move. A sphere or cylinder is
    // built again at its original centre plus this offset (see origin), so its own geometry stays exact. Longs: the
    // box moved from any int position to any other.
    private val ox: Long = 0,
    private val oy: Long = 0,
    private val oz: Long = 0,
    // Sphere: the centre and radius it was first made with (x, y, z, radius); cylinder: also its height. The
    // predicate of such an area already tests the moved shape, so no offset is applied to the position.
    private val origin: DoubleArray? = null,
) {
    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) {
            "Area bounds are inverted: [$minX,$minY,$minZ]..[$maxX,$maxY,$maxZ]"
        }
        val spanX = (maxX shr 4) - (minX shr 4) + 1
        val spanZ = (maxZ shr 4) - (minZ shr 4) + 1
        require(spanX <= MAX_CHUNK_SPAN && spanZ <= MAX_CHUNK_SPAN) {
            "Area spans $spanX x $spanZ chunks; the limit is $MAX_CHUNK_SPAN x $MAX_CHUNK_SPAN. " +
                "Split it into smaller areas or use a coarser design."
        }
    }

    /** True for areas whose predicate refines the box: [custom], [sphere] and [cylinder]. */
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
        return predicateAccepts(x, y, z)
    }

    /** The custom predicate only (true for a box). The engine applies it after its own box check. */
    internal fun predicateAccepts(x: Double, y: Double, z: Double): Boolean =
        predicate == null || (if (origin != null) predicate.test(x, y, z) else predicate.test(x - ox, y - oy, z - oz))

    /**
     * A copy of this area moved by whole blocks, in the same world: the box and, for a custom shape, sphere or
     * cylinder, the shape move together. A sphere or cylinder becomes the same shape at the moved centre (its original
     * centre plus all the moves so far, so repeated moves don't add up rounding and moving back gives the original
     * shape); a custom predicate is asked with the position moved back, so it keeps its own coordinates. The copy is
     * a new area. Translating a translated area doesn't nest anything, so moving a shape every tick costs the same
     * each time. For fractional moves, build the shape again at its new centre. Throws [IllegalArgumentException] when
     * the moved area would leave the int block coordinate range.
     */
    fun translate(dx: Int, dy: Int, dz: Int): Area {
        val o = origin
        if (o != null) {
            return if (o.size == 4) sphereAt(worldId, o[0], o[1], o[2], o[3], ox + dx, oy + dy, oz + dz)
            else cylinderAt(worldId, o[0], o[1], o[2], o[3], o[4], ox + dx, oy + dy, oz + dz)
        }
        try {
            return Area(
                worldId,
                Math.addExact(minX, dx), Math.addExact(minY, dy), Math.addExact(minZ, dz),
                Math.addExact(maxX, dx), Math.addExact(maxY, dy), Math.addExact(maxZ, dz),
                predicate, shape, ox + dx, oy + dy, oz + dz,
            )
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("translating $this by $dx, $dy, $dz leaves the int coordinate range", e)
        }
    }

    override fun toString(): String = "Area($shape $worldId [$minX,$minY,$minZ]..[$maxX,$maxY,$maxZ])"

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

        /**
         * A ball: every position whose distance from ([x], [y], [z]) is at most [radius] (the surface counts as
         * inside). Players are tested at their feet position, like in every area. [radius] must be positive and
         * finite, and the ball within the [MAX_CHUNK_SPAN] limit (a radius up to about 500 blocks). Built on [custom]:
         * the box indexed is every block the ball reaches, `floor(centre - radius)` to `floor(centre + radius)` on
         * each axis, and the distance test refines it (a few multiplications). Meant for real sizes, a fraction of a
         * block and up: the test compares squared distances, which lose their meaning for radii near 1e-150 and below.
         */
        @JvmStatic
        fun sphere(worldId: UUID, x: Double, y: Double, z: Double, radius: Double): Area =
            sphereAt(worldId, x, y, z, radius, 0, 0, 0)

        /** The sphere made at ([x0], [y0], [z0]), moved by whole blocks: built at the moved centre, fully checked. */
        private fun sphereAt(worldId: UUID, x0: Double, y0: Double, z0: Double, radius: Double, ox: Long, oy: Long, oz: Long): Area {
            checkCentre(x0, y0, z0)
            require(radius.isFinite() && radius > 0.0) { "radius must be positive and finite, got $radius" }
            val x = x0 + ox
            val y = y0 + oy
            val z = z0 + oz
            checkFits(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)
            val r2 = radius * radius
            return Area(
                worldId,
                floor(x - radius).toInt(), floor(y - radius).toInt(), floor(z - radius).toInt(),
                floor(x + radius).toInt(), floor(y + radius).toInt(), floor(z + radius).toInt(),
                AreaPredicate { px, py, pz ->
                    val dx = px - x
                    val dy = py - y
                    val dz = pz - z
                    dx * dx + dy * dy + dz * dz <= r2
                },
                "sphere", ox, oy, oz, doubleArrayOf(x0, y0, z0, radius),
            )
        }

        @JvmStatic
        fun sphere(world: World, x: Double, y: Double, z: Double, radius: Double): Area = sphere(world.uid, x, y, z, radius)

        @JvmStatic
        fun sphere(world: World, center: Vector, radius: Double): Area = sphere(world.uid, center.x, center.y, center.z, radius)

        /** A [sphere] around [center], in its world (which must be set). */
        @JvmStatic
        fun sphere(center: Location, radius: Double): Area =
            sphere(requireNotNull(center.world) { "center has no world" }.uid, center.x, center.y, center.z, radius)

        /**
         * An upright cylinder standing on its centre: positions whose horizontal distance from ([x], [z]) is at most
         * [radius], from height [y] (its base, for example a platform's floor) up to `y + height`. The side, the base
         * and the top count as inside. Players are tested at their feet position, so `y` is where they stand. [radius]
         * must be positive, [height] zero or more (both finite), and the cylinder within the [MAX_CHUNK_SPAN] limit.
         * Built on [custom] like [sphere]: the box is every block it reaches, `floor(y)` to `floor(y + height)` high.
         * The radius is compared squared, with the same lower limit as [sphere].
         */
        @JvmStatic
        fun cylinder(worldId: UUID, x: Double, y: Double, z: Double, radius: Double, height: Double): Area =
            cylinderAt(worldId, x, y, z, radius, height, 0, 0, 0)

        /** The cylinder made on ([x0], [y0], [z0]), moved by whole blocks: built on the moved base, fully checked. */
        private fun cylinderAt(
            worldId: UUID, x0: Double, y0: Double, z0: Double, radius: Double, height: Double, ox: Long, oy: Long, oz: Long,
        ): Area {
            checkCentre(x0, y0, z0)
            require(radius.isFinite() && radius > 0.0) { "radius must be positive and finite, got $radius" }
            require(height.isFinite() && height >= 0.0) { "height must be zero or more and finite, got $height" }
            val x = x0 + ox
            val y = y0 + oy
            val z = z0 + oz
            val r2 = radius * radius
            val top = y + height
            checkFits(x - radius, y, z - radius, x + radius, top, z + radius)
            return Area(
                worldId,
                floor(x - radius).toInt(), floor(y).toInt(), floor(z - radius).toInt(),
                floor(x + radius).toInt(), floor(top).toInt(), floor(z + radius).toInt(),
                AreaPredicate { px, py, pz ->
                    val dx = px - x
                    val dz = pz - z
                    py >= y && py <= top && dx * dx + dz * dz <= r2
                },
                "cylinder", ox, oy, oz, doubleArrayOf(x0, y0, z0, radius, height),
            )
        }

        @JvmStatic
        fun cylinder(world: World, x: Double, y: Double, z: Double, radius: Double, height: Double): Area =
            cylinder(world.uid, x, y, z, radius, height)

        @JvmStatic
        fun cylinder(world: World, center: Vector, radius: Double, height: Double): Area =
            cylinder(world.uid, center.x, center.y, center.z, radius, height)

        /** A [cylinder] standing on [center], in its world (which must be set). */
        @JvmStatic
        fun cylinder(center: Location, radius: Double, height: Double): Area =
            cylinder(requireNotNull(center.world) { "center has no world" }.uid, center.x, center.y, center.z, radius, height)

        private fun checkCentre(x: Double, y: Double, z: Double) {
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "centre must be finite, got $x, $y, $z" }
        }

        /**
         * The block of each bound of the shape's box (its floor) must be an int: `floor(b)` in
         * `Int.MIN_VALUE..Int.MAX_VALUE`, so no bound is saturated by the conversion.
         */
        private fun checkFits(vararg bounds: Double) {
            for (b in bounds) {
                require(b >= Int.MIN_VALUE.toDouble() && b < Int.MAX_VALUE.toDouble() + 1.0) {
                    "the shape reaches $b, past the int block coordinate range"
                }
            }
        }
    }
}

/** The exact shape of a [Area.custom] area: whether a position (in block coordinates) is inside. */
fun interface AreaPredicate {
    fun test(x: Double, y: Double, z: Double): Boolean
}
