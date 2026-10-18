package com.sneakyutils.util

import java.util.Locale

/**
 * Bukkit-free aliases for `AttributeModifier.Operation` config tokens.
 */
object AttributeOperationNames {

    const val ADD_NUMBER = "add_number"
    const val ADD_SCALAR = "add_scalar"
    const val MULTIPLY_SCALAR_1 = "multiply_scalar_1"

    private val ALIASES: Map<String, String> = buildMap {
        putCanonical(ADD_NUMBER, "add_number", "addnumber")
        putCanonical(ADD_SCALAR, "add_scalar", "addscalar")
        putCanonical(
            MULTIPLY_SCALAR_1,
            "multiply_scalar_1",
            "multiplyscalar1",
            "multiply_scalar",
            "multiplyscalar",
        )
    }

    private fun MutableMap<String, String>.putCanonical(canonical: String, vararg aliases: String) {
        put(canonical, canonical)
        for (alias in aliases) {
            put(alias, canonical)
        }
    }

    /**
     * @return canonical operation name, or `null` if unknown
     */
    @JvmStatic
    fun canonicalize(operation: String?): String? {
        if (operation.isNullOrBlank()) return null
        return ALIASES[operation.trim().lowercase(Locale.ROOT)]
    }
}
