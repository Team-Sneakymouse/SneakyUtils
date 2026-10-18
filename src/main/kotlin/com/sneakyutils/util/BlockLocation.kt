package com.sneakyutils.util

import org.bukkit.Location
import java.util.Objects

class BlockLocation(
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {

    constructor(location: Location) : this(
        location.world!!.name,
        location.blockX,
        location.blockY,
        location.blockZ,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as BlockLocation
        return x == other.x && y == other.y && z == other.z && worldName == other.worldName
    }

    override fun hashCode(): Int = Objects.hash(worldName, x, y, z)

    override fun toString(): String = "$worldName,$x,$y,$z"

    companion object {
        @JvmStatic
        fun fromString(str: String): BlockLocation? {
            val split = str.split(",")
            if (split.size != 4) return null
            return try {
                BlockLocation(split[0], split[1].toInt(), split[2].toInt(), split[3].toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
