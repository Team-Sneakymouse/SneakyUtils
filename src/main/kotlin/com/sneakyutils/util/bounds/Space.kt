package com.sneakyutils.util.bounds

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity

interface Space {

    fun contains(location: Location): Boolean

    fun contains(entity: Entity): Boolean

    fun contains(block: Block): Boolean
}
