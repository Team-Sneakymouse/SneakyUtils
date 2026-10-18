package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.BlockCommandSender
import org.bukkit.entity.Entity
import org.bukkit.util.NumberConversions
import org.bukkit.util.Vector

object LocationUtil {

    // -------------------------------------------- //
    // IS SAME X LOGIC
    // -------------------------------------------- //

    @JvmStatic
    fun isSameWorld(loc1: Any?, loc2: Any?): Boolean {
        val world1 = getWorld(loc1) ?: return false
        val world2 = getWorld(loc2) ?: return false
        return world1 == world2
    }

    @JvmStatic
    fun isSameBlock(loc1: Any?, loc2: Any?): Boolean {
        val location1 = getLocation(loc1) ?: return false
        val location2 = getLocation(loc2) ?: return false
        if (location1.world != location2.world) return false
        if (location1.blockX != location2.blockX) return false
        if (location1.blockY != location2.blockY) return false
        if (location1.blockZ != location2.blockZ) return false
        return true
    }

    @JvmStatic
    fun isSameChunk(one: Any?, two: Any?): Boolean {
        val location1 = getLocation(one) ?: return false
        val location2 = getLocation(two) ?: return false
        if (location1.blockX shr 4 != location2.blockX shr 4) return false
        if (location1.blockY shr 4 != location2.blockY shr 4) return false
        if (location1.blockZ shr 4 != location2.blockZ shr 4) return false
        return location1.world == location2.world
    }

    // -------------------------------------------- //
    // DISTANCE
    // -------------------------------------------- //

    // Returns -1.0 if something didn't work
    // TODO see if this should do the vector convert first for cross world
    @JvmStatic
    fun distanceSquared(one: Any?, two: Any?): Double {
        val location1 = getLocation(one) ?: return -1.0
        val location2 = getLocation(two) ?: return -1.0

        return try {
            location1.distanceSquared(location2)
        } catch (_: Exception) {
            // In case we had some issue with distances between two worlds
            -1.0
        }
    }

    @JvmStatic
    fun distanceLessThan(one: Any?, two: Any?, distance: Double): Boolean {
        val actualDistanceSquared = distanceSquared(one, two)
        if (actualDistanceSquared == -1.0) return false
        return actualDistanceSquared < distance * distance
    }

    @JvmStatic
    fun distanceGreaterThan(one: Any?, two: Any?, distance: Double): Boolean {
        val actualDistanceSquared = distanceSquared(one, two)
        if (actualDistanceSquared == -1.0) return false
        return actualDistanceSquared > distance * distance
    }

    // -------------------------------------------- //
    // COMMON COMBINED LOGIC
    // -------------------------------------------- //

    // Are the locations in a different world or further away than distance
    // Returns false if either of the locations are null
    @JvmStatic
    fun differentWorldDistanceGreaterThan(location1: Any?, location2: Any?, distance: Double): Boolean {
        val loc1 = getLocation(location1) ?: return false
        val loc2 = getLocation(location2) ?: return false
        if (!isSameWorld(loc1, loc2)) return true
        return distanceGreaterThan(loc1, loc2, distance)
    }

    // -------------------------------------------- //
    // EXTRACTOR LOGIC
    // -------------------------------------------- //

    // This should redirect to other internal methods depending on what the type of object is
    @JvmStatic
    fun getLocation(obj: Any?): Location? {
        if (obj == null) return null

        if (obj is Location) return obj
        if (obj is Entity) return obj.location
        if (obj is Block) return obj.location
        if (obj is BlockCommandSender) return getLocation(obj.block)

        return null
    }

    // This should redirect to other internal methods depending on what the type of object is
    @JvmStatic
    fun getWorld(obj: Any?): World? {
        if (obj == null) return null

        if (obj is World) return obj
        if (obj is Location) return obj.world
        if (obj is String) return Bukkit.getServer().getWorld(obj)
        if (obj is Entity) return obj.world
        if (obj is Block) return obj.world
        if (obj is BlockCommandSender) return getWorld(obj.block)

        return null
    }

    @JvmStatic
    fun setDirection(loc: Location, v: Vector): Location {
        val twoPi = 2 * Math.PI
        val x = v.x
        val z = v.z

        if (x == 0.0 && z == 0.0) {
            loc.pitch = if (v.y > 0) -90f else 90f
            return loc
        }

        val theta = Math.atan2(-x, z)
        loc.yaw = Math.toDegrees((theta + twoPi) % twoPi).toFloat()

        val x2 = NumberConversions.square(x)
        val z2 = NumberConversions.square(z)
        val xz = Math.sqrt(x2 + z2)
        loc.pitch = Math.toDegrees(Math.atan(-v.y / xz)).toFloat()

        return loc
    }

    // Returns a vector pointing from startLoc to endLoc
    @JvmStatic
    fun getDirection(startLoc: Location, endLoc: Location): Vector =
        endLoc.toVector().subtract(startLoc.toVector()).normalize()

    // Returns a cloned location with updated direction vector
    @JvmStatic
    fun updateDirection(location: Location, direction: Vector): Location =
        location.clone().setDirection(direction)

    // Returns a cloned startLoc with updated direction, pointing at endLoc
    @JvmStatic
    fun getRotatedLocation(startLoc: Location, endLoc: Location): Location =
        updateDirection(startLoc, getDirection(startLoc, endLoc))
}
