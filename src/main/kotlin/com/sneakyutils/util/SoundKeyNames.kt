package com.sneakyutils.util

import java.util.Locale

/**
 * Bukkit-free sound name → `namespace:path` shaping for [SoundUtil].
 */
object SoundKeyNames {

    /**
     * @return namespaced key string (`namespace:path`), or `null` if blank/invalid
     */
    @JvmStatic
    fun toNamespacedKeyString(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        if (trimmed.contains(":")) {
            return trimmed.lowercase(Locale.ROOT)
        }
        if (trimmed.contains(".")) {
            return "minecraft:" + trimmed.lowercase(Locale.ROOT)
        }
        return "minecraft:" + trimmed.lowercase(Locale.ROOT).replace('_', '.')
    }

    @JvmStatic
    fun requireNamespacedKeyString(name: String?): String {
        val key = toNamespacedKeyString(name)
        return key ?: throw NullPointerException("sound name")
    }
}
