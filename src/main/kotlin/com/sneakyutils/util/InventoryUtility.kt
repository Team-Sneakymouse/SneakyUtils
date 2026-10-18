package com.sneakyutils.util

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/**
 * Inventory serialization helpers (Paper YAML primary, legacy BukkitObject Base64, Paper per-item Base64).
 */
object InventoryUtility {

    /** Decodes Base64 that may include legacy line wrapping / whitespace (old SnakeYAML Base64Coder). */
    private val LEGACY_BASE64 = Base64.getMimeDecoder()

    /** Prefer Paper YAML for new saves. */
    @JvmStatic
    fun convertInventory(inventory: Inventory): String =
        inventoryToPaperYaml(inventory)

    /**
     * Auto-detect Paper YAML vs legacy Base64.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun getSavedInventory(data: String?): Array<ItemStack?> {
        if (data.isNullOrBlank()) {
            return arrayOf()
        }
        if (looksLikePaperYaml(data)) {
            return inventoryFromPaperYaml(data)
        }
        return getSavedInventoryStrict(data)
    }

    @JvmStatic
    fun looksLikePaperYaml(data: String): Boolean =
        data.contains("format: paper-native") ||
            data.contains("format: paper-native-collection") ||
            (data.contains("size:") && data.contains("items:"))

    @JvmStatic
    fun inventoryToPaperYaml(inventory: Inventory): String =
        inventoryToPaperYaml(inventory.contents)

    @JvmStatic
    fun inventoryToPaperYaml(items: Array<out ItemStack?>): String {
        val config = YamlConfiguration()
        config.set("size", items.size)
        config.set("format", "paper-native-collection")
        val serializedItems = ItemStack.serializeItemsAsBytes(items.toList())
        config.set("items", Base64.getEncoder().encodeToString(serializedItems))
        return config.saveToString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun inventoryFromPaperYaml(yamlString: String): Array<ItemStack?> {
        try {
            val config = YamlConfiguration()
            config.loadFromString(yamlString)

            val size = config.getInt("size", 36)
            val format = config.getString("format", "unknown")

            if ("paper-native-collection" == format) {
                val base64Data = config.getString("items") ?: return arrayOfNulls(size)
                val serializedItems = Base64.getDecoder().decode(base64Data)
                val deserialized = ItemStack.deserializeItemsFromBytes(serializedItems)
                return Array(deserialized.size) { i -> deserialized[i] }
            }

            val items = arrayOfNulls<ItemStack>(size)
            if (config.isConfigurationSection("items")) {
                for (key in config.getConfigurationSection("items")!!.getKeys(false)) {
                    val slot = key.toInt()
                    if (slot < 0 || slot >= size) continue
                    val base64Data = config.getString("items.$key") ?: continue
                    items[slot] = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64Data))
                }
            }
            return items
        } catch (e: Exception) {
            throw IOException("Could not decode Paper YAML inventory", e)
        }
    }

    @JvmStatic
    fun inventoryPaperToBase64(inventory: Inventory): List<String> {
        val result = ArrayList<String>()
        for (itemStack in inventory.contents) {
            if (itemStack == null) {
                result.add("")
            } else {
                result.add(Base64.getEncoder().encodeToString(itemStack.serializeAsBytes()))
            }
        }
        return result
    }

    @JvmStatic
    fun inventoryPaperFromBase64(data: List<String?>): List<ItemStack> {
        val result = ArrayList<ItemStack>()
        for (s in data) {
            if (s.isNullOrEmpty()) {
                result.add(ItemStack(Material.AIR))
            } else {
                result.add(ItemStack.deserializeBytes(LEGACY_BASE64.decode(s)))
            }
        }
        return result
    }

    @JvmStatic
    fun inventoryToBase64(inventory: Inventory): String =
        inventoryToBase64(inventory.size, inventory.contents)

    @JvmStatic
    fun inventoryToBase64(size: Int, items: Array<out ItemStack?>): String {
        try {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)
            dataOutput.writeInt(size)
            for (i in 0 until size) {
                dataOutput.writeObject(if (i < items.size) items[i] else null)
            }
            dataOutput.close()
            // MIME encoder keeps legacy line wrapping compatible with old Base64Coder.encodeLines data.
            return Base64.getMimeEncoder().encodeToString(outputStream.toByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("Could not convert inventory to base64.", e)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun inventoryFromBase64(data: String): Array<ItemStack?> =
        getSavedInventoryStrict(data)

    @JvmStatic
    @Throws(IOException::class)
    fun getSavedInventoryStrict(data: String): Array<ItemStack?> {
        try {
            val inputStream = ByteArrayInputStream(LEGACY_BASE64.decode(data))
            BukkitObjectInputStream(inputStream).use { dataInput ->
                val size = dataInput.readInt()
                if (size < 0 || size > 54) {
                    throw IOException("Saved inventory has invalid size $size")
                }
                val items = arrayOfNulls<ItemStack>(size)
                for (i in 0 until size) {
                    @Suppress("UNCHECKED_CAST")
                    items[i] = dataInput.readObject() as ItemStack?
                }
                return items
            }
        } catch (exception: ClassNotFoundException) {
            throw IOException("Could not decode the saved inventory", exception)
        } catch (exception: IllegalArgumentException) {
            throw IOException("Could not decode the saved inventory", exception)
        } catch (exception: ClassCastException) {
            throw IOException("Could not decode the saved inventory", exception)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun migrateBase64ToPaperYaml(base64Data: String): String {
        val oldItems = getSavedInventoryStrict(base64Data)
        return inventoryToPaperYaml(oldItems)
    }
}
