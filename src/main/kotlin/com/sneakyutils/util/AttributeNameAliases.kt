package com.sneakyutils.util

import java.util.Locale

/**
 * Bukkit-free alias expansion for attribute config tokens.
 * Modern Paper keys are bare (`attack_damage`); older configs still use
 * `generic.attack_damage` / `generic_attack_damage` / `GENERIC_ATTACK_DAMAGE`.
 */
object AttributeNameAliases {

    /**
     * Returns lookup candidates for a config token (lowercased, first is the raw form).
     */
    @JvmStatic
    fun candidates(attribute: String?): List<String> {
        if (attribute.isNullOrBlank()) return emptyList()

        val raw = attribute.trim().lowercase(Locale.ROOT)
        val out = linkedSetOf<String>()
        out.add(raw)
        out.add(raw.replace('-', '_'))
        out.add(raw.replace('_', '.'))
        out.add(raw.replace('.', '_'))
        out.add(raw.replace("_", ""))
        out.add(raw.replace(".", "").replace("_", ""))

        if (raw.startsWith("generic.")) {
            val stripped = raw.substring("generic.".length)
            out.add(stripped)
            out.add(stripped.replace('.', '_'))
            out.add(stripped.replace("_", ""))
        }
        if (raw.startsWith("generic_")) {
            val stripped = raw.substring("generic_".length)
            out.add(stripped)
            out.add("generic.$stripped")
            out.add(stripped.replace('_', '.'))
            out.add(stripped.replace("_", ""))
        }

        // Bare modern key → legacy generic forms (for reverse registration).
        if (!raw.contains(".") && !raw.startsWith("generic")) {
            out.add("generic.$raw")
            out.add("generic_$raw")
        }

        return out.toList()
    }
}
