package net.sneakycharactermanager.paper.handlers.character

import org.bukkit.entity.Player

/** Contract fixture, not the SCM implementation. */
class Character(private val plainName: String?) {
    fun getNameUnformatted(): String? {
        if (failNameLookup) throw NoSuchMethodError("incompatible SCM dependency")
        return plainName
    }

    companion object {
        var failNameLookup = false
        val characters = mutableMapOf<Player, Character>()

        @JvmStatic
        fun get(player: Player): Character? = characters[player]
    }
}
