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

    /** Only granted nodes count, and only those whose whole remainder after `permissionNode.` is a number. */
    @JvmStatic
    fun getMaxNumber(permissible: Permissible, permissionNode: String): Int {
        val prefix = "$permissionNode."
        var max: Int? = null
        for (p in permissible.effectivePermissions) {
            if (!p.value || !p.permission.startsWith(prefix, ignoreCase = true)) continue
            val n = p.permission.substring(prefix.length).toIntOrNull() ?: continue
            if (max == null || n > max) max = n
        }
        return max ?: 0
    }

    @JvmStatic
    fun playerHasNode(playerUUID: String, permissionNode: String): Boolean {
        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return false
        return hasNode(player, permissionNode)
    }

    /** Whether exactly this node is set and granted (true) on the permissible. */
    @JvmStatic
    fun hasNode(permissible: Permissible, permissionNode: String): Boolean =
        permissible.effectivePermissions.any { p -> p.value && p.permission.equals(permissionNode, ignoreCase = true) }
}
