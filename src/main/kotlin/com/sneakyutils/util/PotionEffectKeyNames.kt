package com.sneakyutils.util

import java.util.Locale

/**
 * Bukkit-free potion-effect name → `namespace:path` shaping for registry lookup.
 * Includes legacy Bukkit `PotionEffectType` names that differ from modern registry keys.
 */
object PotionEffectKeyNames {

    /**
     * Legacy Bukkit `getName()` tokens → modern `Registry.MOB_EFFECT` path keys.
     * Names that already match the registry (e.g. `speed`) are omitted.
     */
    @JvmField
    val LEGACY_TO_MODERN: Map<String, String> = mapOf(
        "slow" to "slowness",
        "fast_digging" to "haste",
        "slow_digging" to "mining_fatigue",
        "increase_damage" to "strength",
        "heal" to "instant_health",
        "harm" to "instant_damage",
        "jump" to "jump_boost",
        "confusion" to "nausea",
        "damage_resistance" to "resistance",
    )

    /**
     * Canonicalizes a bare effect path (no namespace): lowercases, spaces→underscores,
     * and remaps known legacy Bukkit names.
     *
     * @return modern registry path, or `null` if blank
     */
    @JvmStatic
    fun canonicalizePath(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val path = name.trim().lowercase(Locale.ROOT).replace(' ', '_')
        return LEGACY_TO_MODERN.getOrDefault(path, path)
    }

    /**
     * @return namespaced key string, or `null` if blank
     */
    @JvmStatic
    fun toNamespacedKeyString(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim().lowercase(Locale.ROOT)
        if (trimmed.contains(":")) {
            val colon = trimmed.indexOf(':')
            val namespace = trimmed.substring(0, colon)
            val path = canonicalizePath(trimmed.substring(colon + 1)) ?: return null
            return "$namespace:$path"
        }
        val path = canonicalizePath(trimmed) ?: return null
        return "minecraft:$path"
    }
}
