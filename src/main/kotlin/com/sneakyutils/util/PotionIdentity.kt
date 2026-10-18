package com.sneakyutils.util

import java.util.Locale

/**
 * Bukkit-free potion-data grammar for magic items / cast items.
 * Accepts legacy `TYPE [extended|upgraded]` and modern `LONG_*` / `STRONG_*`
 * names; always canonicalizes to an uppercase `PotionType`-style name.
 */
object PotionIdentity {

    /**
     * Parses a potion-data config string into a canonical uppercase type name.
     *
     * @throws IllegalArgumentException if the input is blank, has invalid flags, or mixes
     *                                  modern prefixes with legacy flags
     */
    @JvmStatic
    fun parse(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("potion-data is empty")
        }

        val args = trimmed.split("\\s+".toRegex())
        val type = args[0].uppercase(Locale.ROOT)
        val modern = type.startsWith("LONG_") || type.startsWith("STRONG_")

        var extended = false
        var upgraded = false
        if (args.size > 1) {
            val flag = args[1]
            when {
                flag.equals("extended", ignoreCase = true) -> extended = true
                flag.equals("upgraded", ignoreCase = true) -> upgraded = true
                else -> throw IllegalArgumentException("unknown potion-data flag '$flag'")
            }
        }
        if (args.size > 2) {
            throw IllegalArgumentException("potion-data has too many tokens")
        }
        if (extended && upgraded) {
            throw IllegalArgumentException("potion-data cannot be both extended and upgraded")
        }
        if (modern && (extended || upgraded)) {
            throw IllegalArgumentException("modern potion type cannot use extended/upgraded flags")
        }
        if (extended) return "LONG_$type"
        if (upgraded) return "STRONG_$type"
        return type
    }

    /**
     * Serializes a canonical type name for config / magic-item strings (lowercase, no flags).
     */
    @JvmStatic
    fun serialize(canonicalTypeName: String): String {
        val trimmed = canonicalTypeName.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("canonical type name is empty")
        }
        return trimmed.lowercase(Locale.ROOT)
    }
}
