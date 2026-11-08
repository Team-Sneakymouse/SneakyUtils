package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * Soft PlaceholderAPI expansion for consumer plugins that already depend on SneakyUtils.
 * Call on the server thread. Returns [text] unchanged when PAPI is absent or expansion fails.
 */
object PlaceholderUtil {
    @JvmStatic
    fun isAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")

    @JvmStatic
    fun setPlaceholders(player: OfflinePlayer, text: String?): String? {
        if (text == null || text.isEmpty() || text.indexOf('%') < 0) return text
        if (!isAvailable()) return text
        return try {
            me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text)
        } catch (_: RuntimeException) {
            text
        } catch (_: LinkageError) {
            text
        }
    }
}
