package com.sneakyutils.triggers

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import java.util.UUID

/**
 * A block position in a world, by world UUID (so it never holds a world loaded).
 *
 * [asLong] packs x, y and z into one long like Minecraft's `BlockPos.asLong`: x in 26 bits, z in 26 bits, y in 12
 * bits. That covers x and z in [-33,554,432, 33,554,431] (the world border stops at 30,000,000) and y in
 * [-2048, 2047] (every legal world height, -2032..2031 at most). A key outside that range still works as a value,
 * for example a step watch above y 2047, but can't be packed: [asLong] throws, and the click and break indexes
 * reject it at registration.
 */
class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int) {

    /** Whether this position fits [asLong]. */
    val isPackable: Boolean get() = isPackable(x, y, z)

    /** The packed position (no world). Throws [IllegalStateException] when not [isPackable]. */
    fun asLong(): Long {
        check(isPackable) { "BlockKey $this is outside the packable range" }
        return pack(x, y, z)
    }

    /** The block, or null when the world isn't loaded. Loads the chunk if needed, like `World.getBlockAt`. */
    fun block(): Block? = org.bukkit.Bukkit.getWorld(worldId)?.getBlockAt(x, y, z)

    override fun equals(other: Any?): Boolean =
        this === other || (other is BlockKey && x == other.x && y == other.y && z == other.z && worldId == other.worldId)

    override fun hashCode(): Int = ((worldId.hashCode() * 31 + x) * 31 + y) * 31 + z

    override fun toString(): String = "BlockKey($worldId, $x, $y, $z)"

    companion object {
        const val MIN_XZ = -(1 shl 25)
        const val MAX_XZ = (1 shl 25) - 1
        const val MIN_Y = -(1 shl 11)
        const val MAX_Y = (1 shl 11) - 1

        private const val XZ_BITS = 26
        private const val Y_BITS = 12
        private const val X_SHIFT = Y_BITS + XZ_BITS // 38
        private const val Z_SHIFT = Y_BITS // 12
        private const val XZ_MASK = (1L shl XZ_BITS) - 1
        private const val Y_MASK = (1L shl Y_BITS) - 1

        @JvmStatic
        fun of(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)

        /** The block containing [location]; its world must be set. */
        @JvmStatic
        fun of(location: Location): BlockKey {
            val world = requireNotNull(location.world) { "location has no world" }
            return BlockKey(world.uid, location.blockX, location.blockY, location.blockZ)
        }

        @JvmStatic
        fun of(world: World, x: Int, y: Int, z: Int): BlockKey = BlockKey(world.uid, x, y, z)

        @JvmStatic
        fun isPackable(x: Int, y: Int, z: Int): Boolean =
            x in MIN_XZ..MAX_XZ && z in MIN_XZ..MAX_XZ && y in MIN_Y..MAX_Y

        /**
         * Packs a position like `BlockPos.asLong`. Lossless only when [isPackable]; outside that range the high bits
         * are dropped (the same wrap Minecraft has), so check first.
         */
        @JvmStatic
        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and XZ_MASK) shl X_SHIFT) or ((z.toLong() and XZ_MASK) shl Z_SHIFT) or (y.toLong() and Y_MASK)

        @JvmStatic
        fun unpackX(packed: Long): Int = (packed shr X_SHIFT).toInt()

        @JvmStatic
        fun unpackY(packed: Long): Int = ((packed shl (64 - Y_BITS)) shr (64 - Y_BITS)).toInt()

        @JvmStatic
        fun unpackZ(packed: Long): Int = ((packed shl (64 - X_SHIFT)) shr (64 - XZ_BITS)).toInt()
    }
}
