package net.sneakycharactermanager.paper.handlers.character

import org.bukkit.entity.Player

/** Contract fixture, not the SCM implementation. */
class Character(private val rawName: String?) {
    fun getName(): String? {
        if (failNameLookup) throw NoSuchMethodError("incompatible SCM dependency")
        return rawName
    }

    // Mirrors ChatUtility.stripFormatting at SCM fbb7aa3; preprocessing destroys expanded hex.
    fun getNameUnformatted(): String? = getName()?.replace(Regex("<[^>]*>|&[0-9A-FK-ORa-fk-or]"), "")

    companion object {
        var failNameLookup = false
        val characters = mutableMapOf<Player, Character>()

        @JvmStatic
        fun get(player: Player): Character? = characters[player]
    }
}
