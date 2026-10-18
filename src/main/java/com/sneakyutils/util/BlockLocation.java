package com.sneakyutils.util;

import java.util.Objects;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.BlockChangeDelegate;

public class BlockLocation {

	private final String worldName;
	private final int x;
	private final int y;
	private final int z;

	public BlockLocation(String worldName, int x, int y, int z) {
		this.worldName = worldName;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public BlockLocation(Location location) {
		this.worldName = location.getWorld().getName();
		this.x = location.getBlockX();
		this.y = location.getBlockY();
		this.z = location.getBlockZ();
	}

	public String getWorldName() {
		return worldName;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getZ() {
		return z;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		BlockLocation that = (BlockLocation) o;
		return x == that.x && y == that.y && z == that.z && Objects.equals(worldName, that.worldName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(worldName, x, y, z);
	}

	@Override
	public String toString() {
		return worldName + "," + x + "," + y + "," + z;
	}

	public static BlockLocation fromString(String str) {
		String[] split = str.split(",");
		if (split.length != 4) return null;
		try {
			return new BlockLocation(split[0], Integer.parseInt(split[1]), Integer.parseInt(split[2]), Integer.parseInt(split[3]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

}
