package com.sneakyutils.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class PlayerNameUtils {

	public static Player getPlayerExact(String playerName) {
		//TODO come up with a non depreciated system
		return Bukkit.getPlayerExact(playerName);
	}
	
	public static Player getPlayer(String playername) {
		//TODO come up with a non depreciated system
		return Bukkit.getPlayer(playername);
	}
	
}
