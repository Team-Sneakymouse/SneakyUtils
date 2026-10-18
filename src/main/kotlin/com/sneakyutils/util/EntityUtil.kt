package com.sneakyutils.util

import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity

object EntityUtil {

    @JvmStatic
    fun asLiving(entity: Entity): LivingEntity? =
        entity as? LivingEntity
}
