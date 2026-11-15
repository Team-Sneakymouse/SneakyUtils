package com.sneakyutils.util

/**
 * The fixed set of causes an [AlteredBlockManager.Change] may carry. Any other string is rejected.
 *
 * Some causes imply others: [EXPLOSION] and [FIRE] are also [DAMAGE], so looking up or undoing
 * `"damage"` matches them too. [DECORATION] implies nothing. A change only needs its most specific
 * cause (`"explosion"`, not `"explosion"` and `"damage"`).
 */
object AlteredBlockCauses {
    /** Blocks broken, removed or spoiled: windows smashed, holes dug, cobwebs, graffiti. */
    const val DAMAGE = "damage"

    /** Damage from an explosion. Implies [DAMAGE]. */
    const val EXPLOSION = "explosion"

    /** Damage from fire, including fire blocks placed. Implies [DAMAGE]. */
    const val FIRE = "fire"

    /** Temporary decoration that is not damage. */
    const val DECORATION = "decoration"

    /** Every valid cause. */
    @JvmField
    val ALL: Set<String> = setOf(DAMAGE, EXPLOSION, FIRE, DECORATION)

    /** For each cause, the broader causes it implies. */
    private val IMPLIES: Map<String, Set<String>> = mapOf(
        EXPLOSION to setOf(DAMAGE),
        FIRE to setOf(DAMAGE),
    )

    /** For each cause, itself and every cause that implies it (what a lookup for it matches). */
    private val MATCHED_BY: Map<String, Set<String>> = ALL.associateWith { query ->
        ALL.filterTo(HashSet()) { it == query || query in IMPLIES[it].orEmpty() }
    }

    @JvmStatic
    fun isValid(cause: String): Boolean = cause in ALL

    /** Throws [IllegalArgumentException] naming the first cause not in [ALL]. */
    @JvmStatic
    fun requireValid(causes: Collection<String>) {
        for (cause in causes) {
            require(cause in ALL) { "Unknown altered-block cause '$cause'; valid causes: ${ALL.joinToString()}" }
        }
    }

    /**
     * Every cause a change may carry to match a lookup for any of [query]: `{damage}` becomes
     * `{damage, explosion, fire}`. Throws [IllegalArgumentException] on an unknown cause.
     */
    @JvmStatic
    fun matching(query: Collection<String>): Set<String> {
        requireValid(query)
        val result = HashSet<String>()
        for (cause in query) result.addAll(MATCHED_BY.getValue(cause))
        return result
    }
}
