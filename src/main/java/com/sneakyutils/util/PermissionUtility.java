package com.sneakyutils.util;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

/**
 * Numeric permission-node helpers (e.g. {@code plugin.limit.5} → max 5).
 */
public final class PermissionUtility {

	private PermissionUtility() {
	}

	/**
	 * @return max numeric suffix under {@code permissionNode.}, or {@code 0} if none; {@code -1} if player offline
	 */
	public static int getMaxNumber(String playerUUID, String permissionNode) {
		Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
		if (player == null) return -1;
		return getMaxNumber(player, permissionNode);
	}

	public static int getMaxNumber(Permissible permissible, String permissionNode) {
		String prefix = permissionNode + ".";
		return permissible.getEffectivePermissions().stream()
			.filter(p -> p.getPermission().startsWith(prefix))
			.map(p -> {
				String[] split = p.getPermission().split("\\.");
				return Integer.parseInt(split[split.length - 1]);
			})
			.max(Integer::compare)
			.orElse(0);
	}

	public static boolean playerHasNode(String playerUUID, String permissionNode) {
		Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
		if (player == null) return false;
		return hasNode(player, permissionNode);
	}

	public static boolean hasNode(Permissible permissible, String permissionNode) {
		return permissible.getEffectivePermissions().stream()
			.anyMatch(p -> p.getPermission().contains(permissionNode));
	}
}
