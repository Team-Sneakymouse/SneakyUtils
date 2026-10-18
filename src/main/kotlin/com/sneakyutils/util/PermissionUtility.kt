package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.permissions.Permissible
import java.util.UUID

/**
 * Numeric permission-node helpers (e.g. `plugin.limit.5` → max 5).
 */
object PermissionUtility {

    /**
     * @return max numeric suffix under `permissionNode.`, or `0` if none; `-1` if player offline
     */
    @JvmStatic
    fun getMaxNumber(playerUUID: String, permissionNode: String): Int {
        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return -1
        return getMaxNumber(player, permissionNode)
    }

    @JvmStatic
    fun getMaxNumber(permissible: Permissible, permissionNode: String): Int {
        val prefix = "$permissionNode."
        return permissible.effectivePermissions.stream()
            .filter { p -> p.permission.startsWith(prefix) }
            .map { p ->
                val split = p.permission.split('.')
                split[split.size - 1].toInt()
            }
            .max(Integer::compare)
            .orElse(0)
    }

    @JvmStatic
    fun playerHasNode(playerUUID: String, permissionNode: String): Boolean {
        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return false
        return hasNode(player, permissionNode)
    }

    @JvmStatic
    fun hasNode(permissible: Permissible, permissionNode: String): Boolean =
        permissible.effectivePermissions.stream()
            .anyMatch { p -> p.permission.contains(permissionNode) }
}
