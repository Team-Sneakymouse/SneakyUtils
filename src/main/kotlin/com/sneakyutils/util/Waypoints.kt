package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Points a player at a place with the server's waypoint (`findlocation`), which has no API: the commands in
 * SneakyUtils' `waypoints.commands` config are run as the player, with `%path%` (the waypoint style, e.g. `paladin`,
 * `scrying`), `%world%` (world key), `%x%` `%y%` `%z%` (block coordinates) and `%player%` filled in. Server thread only.
 */
object Waypoints {

    private val DEFAULT_COMMANDS = listOf("findlocation_path %path%", "findlocation %world% %x% %y% %z% 0 0")

    /** Points [player] at [location] in the [path] style. Does nothing when the location has no world. */
    @JvmStatic
    fun guide(player: Player, location: Location, path: String) {
        val world = location.world ?: return
        for (command in commands()) {
            if (command.isBlank()) continue
            player.performCommand(
                command.replace("%path%", path).replace("%world%", world.key.toString())
                    .replace("%x%", location.blockX.toString()).replace("%y%", location.blockY.toString())
                    .replace("%z%", location.blockZ.toString()).replace("%player%", player.name)
            )
        }
    }

    private fun commands(): List<String> {
        val config = SneakyUtils.instance.config
        return if (config.isList("waypoints.commands")) config.getStringList("waypoints.commands") else DEFAULT_COMMANDS
    }
}
