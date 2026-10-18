package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.inventory.ItemStack

object MobUtil {

    private val entityToEggMaterial = HashMap<EntityType, Material>()
    private val entityTypeMap = HashMap<String, EntityType>()

    init {
        for (type in EntityType.entries) {
            if (type == EntityType.UNKNOWN) continue

            entityTypeMap[type.name.lowercase()] = type
            entityTypeMap[type.name.lowercase().replace("_", "")] = type

            entityTypeMap[type.key.key] = type
            entityTypeMap[type.key.key.replace("_", "")] = type

            val material = Material.matchMaterial(type.key.key + "_spawn_egg")
            if (material != null) entityToEggMaterial[type] = material
        }

        val types = HashMap<String, EntityType>()
        for ((key, value) in entityTypeMap) {
            types[key + 's'] = value
        }
        entityTypeMap.putAll(types)
    }

    @JvmStatic
    fun getEntityType(type: String): EntityType? {
        if (type.equals("player", ignoreCase = true)) return EntityType.PLAYER
        return entityTypeMap[type.lowercase()]
    }

    @JvmStatic
    fun getEggItemForEntityType(type: EntityType): ItemStack? {
        val eggMaterial = entityToEggMaterial[type] ?: return null
        return ItemStack(eggMaterial)
    }

    @JvmStatic
    fun hasEggMaterialForEntityType(type: EntityType): Boolean =
        entityToEggMaterial.containsKey(type)

    @JvmStatic
    fun setTarget(mob: LivingEntity, target: LivingEntity?, reason: EntityTargetEvent.TargetReason) {
        val e = EntityTargetEvent(mob, target, reason)
        Bukkit.getPluginManager().callEvent(e)

        if (!e.isCancelled) {
            if (mob is Mob) mob.target = target
        }
    }

    @JvmStatic
    fun setTarget(mob: LivingEntity, target: LivingEntity?) {
        if (mob is Mob) mob.target = target
    }

    @JvmStatic
    fun getPigZombieEntityType(): EntityType =
        try {
            EntityType.valueOf("ZOMBIFIED_PIGLIN")
        } catch (_: IllegalArgumentException) {
            EntityType.valueOf("PIG_ZOMBIE")
        }
}
