package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player

object PlayerNameUtils {

    @JvmStatic
    fun getPlayerExact(playerName: String): Player? {
        // TODO come up with a non depreciated system
        return Bukkit.getPlayerExact(playerName)
    }

    @JvmStatic
    fun getPlayer(playername: String): Player? {
        // TODO come up with a non depreciated system
        return Bukkit.getPlayer(playername)
    }
}
