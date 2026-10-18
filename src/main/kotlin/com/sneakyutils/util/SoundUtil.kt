package com.sneakyutils.util

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound

/**
 * Resolve [Sound] from config tokens without `Sound.valueOf`.
 * Accepts `minecraft:entity.item.pickup`, `entity.item.pickup`,
 * and legacy enum-style `ENTITY_ITEM_PICKUP`.
 */
object SoundUtil {

    @JvmStatic
    fun resolve(name: String?): Sound? {
        val keyString = SoundKeyNames.toNamespacedKeyString(name) ?: return null
        val key = NamespacedKey.fromString(keyString) ?: return null
        return Registry.SOUNDS.get(key)
    }
}
