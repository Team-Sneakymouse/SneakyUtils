package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

object PlayerUtil {
    private const val NAME_PLACEHOLDER = "%sneakycharacters_character_name_noformat%"
    private const val ID_PLACEHOLDER = "%sneakycharacters_character_uuid%"
    private const val VANISH_PLACEHOLDER = "%cmi_user_vanished_symbol%"
    private const val DUTY_PLACEHOLDER = "%sneakydispatch_on_duty%"

    /**
     * SCM's registered noformat output, unchanged. Call on the server thread. Cached per player for
     * `player-util.character-cache-ticks` (dropped on character load, rename and quit).
     *
     * Known limits, bounded by `player-util.character-cache-ticks` (default 20): during an SCM puppet swap, listeners
     * called while SCM applies the bodies' player state (potion effects, air) before the first CharacterLoadedEvent can
     * read the old name; after `chardisable` of a player's own character (no SCM event) the old name can be returned
     * until the entry expires. Set the key to 0 (restart) to turn the cache off if a caller needs exact values, or call
     * [clearCachedChecks] after making such a change.
     */
    @JvmStatic
    fun getCharacterName(player: Player): String {
        if (!characterPlaceholdersAvailable()) return player.name
        return cached(PlayerCheckCache.Check.CHARACTER_NAME, player) {
            PlaceholderUtil.setPlaceholders(player, NAME_PLACEHOLDER)
                .takeUnless { it.isNullOrBlank() || it == NAME_PLACEHOLDER }
        } ?: player.name
    }

    /**
     * The UUID string of the player's active SneakyCharacterManager character (`%sneakycharacters_character_uuid%`),
     * or null when SCM or PlaceholderAPI is unavailable, the player has no character, or the placeholder is
     * unresolved. Call on the server thread. Cached like [getCharacterName], with the same known limits (a puppet
     * swap's state callbacks and `chardisable` of an own character can see the old id for up to
     * `player-util.character-cache-ticks`; set it to 0 for exact values).
     */
    @JvmStatic
    fun characterId(player: Player): String? {
        if (!characterPlaceholdersAvailable()) return null
        return cached(PlayerCheckCache.Check.CHARACTER_ID, player) {
            PlaceholderUtil.setPlaceholders(player, ID_PLACEHOLDER)
                .takeUnless { it.isNullOrBlank() || it == ID_PLACEHOLDER }
        }
    }

    private fun characterPlaceholdersAvailable(): Boolean =
        Bukkit.getPluginManager().isPluginEnabled("SneakyCharacterManager") && PlaceholderUtil.isAvailable()

    /**
     * Server-thread target eligibility. CMI's nonempty, resolved vanish symbol
     * excludes the player; unavailable PAPI/expansions do not imply vanished.
     * The game mode is read on every call; the vanish result is cached per player for
     * `player-util.vanish-cache-ticks` (dropped on CMI vanish/unvanish and quit).
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
        return !cached(PlayerCheckCache.Check.VANISH, player) {
            val symbol = PlaceholderUtil.setPlaceholders(player, VANISH_PLACEHOLDER)
            !(symbol == null || symbol.isEmpty() || symbol == VANISH_PLACEHOLDER)
        }
    }

    /**
     * True when SneakyDispatch's on_duty resolves to true. Dispatch already
     * requires a paladin character. Call on the server thread; unavailable
     * PAPI/expansion returns false. Cached per player for `player-util.duty-cache-ticks` (dropped on
     * [com.sneakyutils.signals.DutyChangedEvent], character loads and quit). Duty follows the worn character, so the
     * [getCharacterName] limits apply too: a puppet swap's state callbacks and `chardisable` of an own character can
     * see the old duty for up to `player-util.duty-cache-ticks` (set it to 0 for exact values).
     */
    @JvmStatic
    fun onDuty(player: Player): Boolean {
        if (!PlaceholderUtil.isAvailable()) return false
        return cached(PlayerCheckCache.Check.DUTY, player) {
            PlaceholderUtil.setPlaceholders(player, DUTY_PLACEHOLDER).equals("true", ignoreCase = true)
        }
    }

    /**
     * Drops every cached check for player (vanish, duty, character id and name), so the next call reads the
     * placeholders again. For plugins that change one of these without an event SneakyUtils hears. Any thread
     * (on the server thread it happens at once and again next tick; elsewhere on the next tick).
     */
    @JvmStatic
    fun clearCachedChecks(player: Player) {
        PlayerCheckCache.active?.invalidateNowAndNextTick(player.uniqueId, PlayerCheckCache.ALL)
    }

    /** Reads through the cache on the server thread while SneakyUtils is enabled and check's cache is on. */
    private inline fun <T> cached(check: PlayerCheckCache.Check, player: Player, compute: () -> T): T {
        val cache = PlayerCheckCache.active?.takeIf { it.enabled(check) && Bukkit.isPrimaryThread() }
        if (cache != null) {
            val hit = cache.lookup(check, player)
            @Suppress("UNCHECKED_CAST")
            if (hit !== PlayerCheckCache.MISS) return hit as T
        }
        val value = compute()
        cache?.store(check, player, value)
        return value
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
