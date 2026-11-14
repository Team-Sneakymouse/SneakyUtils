package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

object PlayerUtil {
    /** SCM's registered noformat output, unchanged. Call on the server thread. */
    @JvmStatic
    fun getCharacterName(player: Player): String {
        if (!Bukkit.getPluginManager().isPluginEnabled("SneakyCharacterManager") ||
            !PlaceholderUtil.isAvailable()) return player.name
        val placeholder = "%sneakycharacters_character_name_noformat%"
        val name = PlaceholderUtil.setPlaceholders(player, placeholder)
        return name.takeUnless { it.isNullOrBlank() || it == placeholder } ?: player.name
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
        if (!PlaceholderUtil.isAvailable()) return true
        val placeholder = "%cmi_user_vanished_symbol%"
        val symbol = PlaceholderUtil.setPlaceholders(player, placeholder) ?: return true
        return symbol.isEmpty() || symbol == placeholder
    }

    /**
     * True when SneakyDispatch's on_duty resolves to true. Dispatch already
     * requires a paladin character. Call on the server thread; unavailable
     * PAPI/expansion returns false.
     */
    @JvmStatic
    fun onDuty(player: Player): Boolean {
        if (!PlaceholderUtil.isAvailable()) return false
        return PlaceholderUtil.setPlaceholders(player, "%sneakydispatch_on_duty%")
            .equals("true", ignoreCase = true)
    }

    @JvmStatic
    fun getPlayerExact(playerName: String): Player? = Bukkit.getPlayerExact(playerName)

    @JvmStatic
    fun getPlayer(playerName: String): Player? = Bukkit.getPlayer(playerName)

    /**
     * Hides player from everyone, themselves included, without spectator mode: invisible, their held items and armour
     * sent to others as empty, their own armour/hotbar/offhand shown to them as empty, and everything riding them
     * (worn cosmetics, nametags) hidden. They stay a real entity and stay in the tab list. Counted per owner: they're
     * revealed only when every plugin that concealed them calls [reveal]. Revealed on quit. Server thread.
     * See [PlayerConcealment].
     */
    @JvmStatic
    fun conceal(owner: Plugin, player: Player) {
        val c = SneakyUtils.getPlayerConcealment() ?: error("SneakyUtils is not enabled; can't conceal players")
        c.conceal(owner, player)
    }

    /** owner stops concealing player (see [conceal]); nothing happens if owner wasn't concealing them. */
    @JvmStatic
    fun reveal(owner: Plugin, player: Player) {
        SneakyUtils.getPlayerConcealment()?.reveal(owner, player)
    }

    /** Whether any plugin conceals player ([conceal]). */
    @JvmStatic
    fun isConcealed(player: Player): Boolean = SneakyUtils.getPlayerConcealment()?.isConcealed(player) == true
}
