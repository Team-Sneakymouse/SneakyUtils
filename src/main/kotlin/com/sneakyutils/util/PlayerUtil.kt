package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player

object PlayerUtil {
    /** SCM's registered noformat output, unchanged. Call on the server thread. */
    @JvmStatic
    fun getCharacterName(player: Player): String {
        val plugins = Bukkit.getPluginManager()
        if (!plugins.isPluginEnabled("SneakyCharacterManager") ||
            !plugins.isPluginEnabled("PlaceholderAPI")) return player.name
        val placeholder = "%sneakycharacters_character_name_noformat%"
        val name = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder)
        return name.takeUnless { it.isBlank() || it == placeholder } ?: player.name
    }

    /**
     * Server-thread target eligibility. CMI's nonempty, resolved vanish symbol
     * excludes the player; unavailable PAPI/expansions do not imply vanished.
     */
    @JvmStatic
    @JvmOverloads
    fun canTarget(player: Player, allowCreative: Boolean = false): Boolean {
        val eligibleMode = when (player.gameMode) {
            org.bukkit.GameMode.SURVIVAL, org.bukkit.GameMode.ADVENTURE -> true
            org.bukkit.GameMode.CREATIVE -> allowCreative
            org.bukkit.GameMode.SPECTATOR -> false
        }
        if (!eligibleMode) return false
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return true
        val placeholder = "%cmi_user_vanished_symbol%"
        val symbol = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder)
        return symbol.isEmpty() || symbol == placeholder
    }

    /**
     * True only when dispatch duty and SCM's paladin tag both resolve to true.
     * Call on the server thread; unavailable PAPI/expansions return false.
     */
    @JvmStatic
    fun onDuty(player: Player): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return false
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%sneakydispatch_on_duty%")
            .equals("true", ignoreCase = true) &&
            me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%sneakycharacters_character_hastag_paladin%")
                .equals("true", ignoreCase = true)
    }

    @JvmStatic
    fun getPlayerExact(playerName: String): Player? = Bukkit.getPlayerExact(playerName)

    @JvmStatic
    fun getPlayer(playerName: String): Player? = Bukkit.getPlayer(playerName)
}
