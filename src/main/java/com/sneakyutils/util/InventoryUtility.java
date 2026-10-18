package com.sneakyutils.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Inventory serialization helpers (Paper YAML primary, legacy BukkitObject Base64, Paper per-item Base64).
 */
public final class InventoryUtility {

	/** Decodes Base64 that may include legacy line wrapping / whitespace (old SnakeYAML Base64Coder). */
	private static final Base64.Decoder LEGACY_BASE64 = Base64.getMimeDecoder();

	private InventoryUtility() {
	}

	/** Prefer Paper YAML for new saves. */
	public static String convertInventory(Inventory inventory) {
		return inventoryToPaperYaml(inventory);
	}

	/**
	 * Auto-detect Paper YAML vs legacy Base64.
	 */
	public static ItemStack[] getSavedInventory(String data) throws IOException {
		if (data == null || data.isBlank()) {
			return new ItemStack[0];
		}
		if (looksLikePaperYaml(data)) {
			return inventoryFromPaperYaml(data);
		}
		return getSavedInventoryStrict(data);
	}

	public static boolean looksLikePaperYaml(String data) {
		return data.contains("format: paper-native")
			|| data.contains("format: paper-native-collection")
			|| (data.contains("size:") && data.contains("items:"));
	}

	public static String inventoryToPaperYaml(Inventory inventory) {
		return inventoryToPaperYaml(inventory.getContents());
	}

	public static String inventoryToPaperYaml(ItemStack[] items) {
		YamlConfiguration config = new YamlConfiguration();
		config.set("size", items.length);
		config.set("format", "paper-native-collection");
		byte[] serializedItems = ItemStack.serializeItemsAsBytes(Arrays.asList(items));
		config.set("items", Base64.getEncoder().encodeToString(serializedItems));
		return config.saveToString();
	}

	public static ItemStack[] inventoryFromPaperYaml(String yamlString) throws IOException {
		try {
			YamlConfiguration config = new YamlConfiguration();
			config.loadFromString(yamlString);

			int size = config.getInt("size", 36);
			String format = config.getString("format", "unknown");

			if ("paper-native-collection".equals(format)) {
				String base64Data = config.getString("items");
				if (base64Data == null) {
					return new ItemStack[size];
				}
				byte[] serializedItems = Base64.getDecoder().decode(base64Data);
				return ItemStack.deserializeItemsFromBytes(serializedItems);
			}

			ItemStack[] items = new ItemStack[size];
			if (config.isConfigurationSection("items")) {
				for (String key : config.getConfigurationSection("items").getKeys(false)) {
					int slot = Integer.parseInt(key);
					if (slot < 0 || slot >= size) continue;
					String base64Data = config.getString("items." + key);
					if (base64Data == null) continue;
					items[slot] = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64Data));
				}
			}
			return items;
		} catch (Exception e) {
			throw new IOException("Could not decode Paper YAML inventory", e);
		}
	}

	public static List<String> inventoryPaperToBase64(Inventory inventory) {
		List<String> result = new ArrayList<>();
		for (ItemStack itemStack : inventory.getContents()) {
			if (itemStack == null) {
				result.add("");
			} else {
				result.add(Base64.getEncoder().encodeToString(itemStack.serializeAsBytes()));
			}
		}
		return result;
	}

	public static List<ItemStack> inventoryPaperFromBase64(List<String> data) {
		List<ItemStack> result = new ArrayList<>();
		for (String s : data) {
			if (s == null || s.isEmpty()) {
				result.add(new ItemStack(Material.AIR));
			} else {
				result.add(ItemStack.deserializeBytes(LEGACY_BASE64.decode(s)));
			}
		}
		return result;
	}

	public static String inventoryToBase64(Inventory inventory) {
		return inventoryToBase64(inventory.getSize(), inventory.getContents());
	}

	public static String inventoryToBase64(int size, ItemStack[] items) {
		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
			dataOutput.writeInt(size);
			for (int i = 0; i < size; i++) {
				dataOutput.writeObject(i < items.length ? items[i] : null);
			}
			dataOutput.close();
			// MIME encoder keeps legacy line wrapping compatible with old Base64Coder.encodeLines data.
			return Base64.getMimeEncoder().encodeToString(outputStream.toByteArray());
		} catch (Exception e) {
			throw new IllegalStateException("Could not convert inventory to base64.", e);
		}
	}

	public static ItemStack[] inventoryFromBase64(String data) throws IOException {
		return getSavedInventoryStrict(data);
	}

	public static ItemStack[] getSavedInventoryStrict(String data) throws IOException {
		try {
			ByteArrayInputStream inputStream = new ByteArrayInputStream(LEGACY_BASE64.decode(data));
			try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
				int size = dataInput.readInt();
				if (size < 0 || size > 54) {
					throw new IOException("Saved inventory has invalid size " + size);
				}
				ItemStack[] items = new ItemStack[size];
				for (int i = 0; i < size; i++) {
					items[i] = (ItemStack) dataInput.readObject();
				}
				return items;
			}
		} catch (ClassNotFoundException | IllegalArgumentException | ClassCastException exception) {
			throw new IOException("Could not decode the saved inventory", exception);
		}
	}

	public static String migrateBase64ToPaperYaml(String base64Data) throws IOException {
		ItemStack[] oldItems = getSavedInventoryStrict(base64Data);
		return inventoryToPaperYaml(oldItems);
	}
}
