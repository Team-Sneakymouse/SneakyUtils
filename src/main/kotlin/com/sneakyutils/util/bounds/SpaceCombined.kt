package com.sneakyutils.util.bounds

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import java.util.LinkedList

class SpaceCombined : LinkedList<Space>, Space {

    constructor(vararg spaces: Space) : this(spaces.toList())

    constructor(spaces: Collection<Space>) : super(spaces)

    override fun contains(location: Location): Boolean =
        any { space -> space.contains(location) }

    override fun contains(entity: Entity): Boolean =
        any { space -> space.contains(entity) }

    override fun contains(block: Block): Boolean =
        any { space -> space.contains(block) }
}
