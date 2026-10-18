package com.sneakyutils.util

import com.sneakyutils.util.bounds.Space
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity

class BoundingBox : Space {

    private var world: World? = null
    private var lowX = 0.0
    private var lowY = 0.0
    private var lowZ = 0.0
    private var highX = 0.0
    private var highY = 0.0
    private var highZ = 0.0
    private var horizontalRadius = 0.0
    private var verticalRadius = 0.0

    constructor(center: Block, radius: Double) :
        this(center.location.add(0.5, 0.0, 0.5), radius, radius)

    constructor(center: Location, radius: Double) :
        this(center, radius, radius)

    constructor(center: Block, horizontalRadius: Double, verticalRadius: Double) :
        this(center.location.add(0.5, 0.0, 0.5), horizontalRadius, verticalRadius)

    constructor(center: Location, horizontalRadius: Double, verticalRadius: Double) {
        this.horizontalRadius = horizontalRadius
        this.verticalRadius = verticalRadius
        setCenter(center)
    }

    constructor(corner1: Location, corner2: Location) {
        world = corner1.world
        lowX = minOf(corner1.x, corner2.x)
        highX = maxOf(corner1.x, corner2.x)
        lowY = minOf(corner1.y, corner2.y)
        highY = maxOf(corner1.y, corner2.y)
        lowZ = minOf(corner1.z, corner2.z)
        highZ = maxOf(corner1.z, corner2.z)
    }

    fun setCenter(center: Location) {
        world = center.world
        lowX = center.x - horizontalRadius
        lowY = center.y - verticalRadius
        lowZ = center.z - horizontalRadius
        highX = center.x + horizontalRadius
        highY = center.y + verticalRadius
        highZ = center.z + horizontalRadius
    }

    fun expand(amount: Double) {
        lowX -= amount
        lowY -= amount
        lowZ -= amount
        highX += amount
        highY += amount
        highZ += amount
    }

    override fun contains(location: Location): Boolean {
        if (location.world != world) return false
        val x = location.x
        val y = location.y
        val z = location.z
        return lowX <= x && x <= highX && lowY <= y && y <= highY && lowZ <= z && z <= highZ
    }

    override fun contains(entity: Entity): Boolean =
        contains(entity.location)

    override fun contains(block: Block): Boolean =
        contains(block.location.add(0.5, 0.0, 0.5))

    fun getHorizontalRadius(): Double = horizontalRadius

    fun getVerticalRadius(): Double = verticalRadius
}
