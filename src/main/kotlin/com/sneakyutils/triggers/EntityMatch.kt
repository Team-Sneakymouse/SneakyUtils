package com.sneakyutils.triggers

import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType

/**
 * Which entities an entity-click subscription accepts. Each factory makes a single test; [withType] adds an entity
 * type restriction to a tag, PDC or Citizens match, which lets the index skip it for every other type.
 *
 * Indexing: a match with a type is listed under that type and only tested for entities of it. A match without a
 * type ("any type") sits in a separate short list that is tested for every clicked entity, so prefer adding a type.
 *
 * Citizens NPCs are detected by the `"NPC"` metadata Citizens sets on its entities; the NPC id is read from the
 * `"NPC-ID"` metadata when present. SneakyUtils has no Citizens dependency. Citizens player NPCs are of type
 * [EntityType.PLAYER], so a click handler meant for real players should use [notCitizens]:
 * `EntityMatch.type(EntityType.PLAYER).notCitizens()`.
 */
class EntityMatch private constructor(
    /** Required entity type, or null for any type. */
    val type: EntityType?,
    /** Required scoreboard tag, or null. */
    val scoreboardTag: String?,
    /** Required PDC key (any value type), or null. */
    val pdcKey: NamespacedKey?,
    /** Whether the entity must be a Citizens NPC. */
    val citizens: Boolean,
    /** Required Citizens NPC id, or null for any NPC (only with [citizens]). */
    val citizensId: Int?,
    /** Whether Citizens NPCs are rejected (see [notCitizens]). */
    val excludesCitizens: Boolean = false,
) {

    /** This match restricted to entities of [type]. */
    fun withType(type: EntityType): EntityMatch = EntityMatch(type, scoreboardTag, pdcKey, citizens, citizensId, excludesCitizens)

    /**
     * This match, rejecting Citizens NPCs (entities with the `"NPC"` metadata). MagicSpells' entity-click passives
     * did this unless they asked for NPCs. Not allowed on a [citizensNpc] match.
     */
    fun notCitizens(): EntityMatch {
        require(!citizens) { "a Citizens NPC match can't also exclude NPCs" }
        return EntityMatch(type, scoreboardTag, pdcKey, false, null, true)
    }

    /** Whether the clicked entity matches. Runs on the server thread. */
    fun matches(entity: Entity): Boolean {
        if (type != null && entity.type != type) return false
        if (scoreboardTag != null && !entity.scoreboardTags.contains(scoreboardTag)) return false
        if (pdcKey != null && !entity.persistentDataContainer.has(pdcKey)) return false
        if (citizens) {
            if (!entity.hasMetadata(CITIZENS_NPC)) return false
            if (citizensId != null && npcId(entity) != citizensId) return false
        }
        if (excludesCitizens && entity.hasMetadata(CITIZENS_NPC)) return false
        return true
    }

    override fun toString(): String = buildString {
        append("EntityMatch(")
        val parts = ArrayList<String>(4)
        if (type != null) parts += "type=$type"
        if (scoreboardTag != null) parts += "tag=$scoreboardTag"
        if (pdcKey != null) parts += "pdc=$pdcKey"
        if (citizens) parts += "citizensNpc=${citizensId ?: "any"}"
        if (excludesCitizens) parts += "notCitizens"
        append(parts.joinToString(", "))
        append(")")
    }

    companion object {
        internal const val CITIZENS_NPC = "NPC"
        internal const val CITIZENS_NPC_ID = "NPC-ID"

        /** Entities of [type]. */
        @JvmStatic
        fun type(type: EntityType): EntityMatch = EntityMatch(type, null, null, false, null)

        /** Entities with the scoreboard tag, of any type (add [withType] when you can). */
        @JvmStatic
        fun scoreboardTag(tag: String): EntityMatch {
            require(tag.isNotEmpty()) { "tag must not be empty" }
            return EntityMatch(null, tag, null, false, null)
        }

        /** Entities whose PDC has [key], of any type (add [withType] when you can). */
        @JvmStatic
        fun pdcKey(key: NamespacedKey): EntityMatch = EntityMatch(null, null, key, false, null)

        /** Citizens NPCs; with an [id], only that NPC. */
        @JvmStatic
        fun citizensNpc(id: Int?): EntityMatch = EntityMatch(null, null, null, true, id)

        private fun npcId(entity: Entity): Int? {
            if (!entity.hasMetadata(CITIZENS_NPC_ID)) return null
            for (value in entity.getMetadata(CITIZENS_NPC_ID)) {
                val raw = value.value()
                if (raw is Number) return raw.toInt()
            }
            return null
        }
    }
}
