package com.sneakyutils.util

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class Wave2UtilTest {

    @Test
    fun blockUtils_classifiesMaterials() {
        assertTrue(BlockUtils.isAir(Material.AIR))
        assertTrue(BlockUtils.isChest(Material.CHEST))
        assertTrue(BlockUtils.isLiquid(Material.WATER))
        assertTrue(BlockUtils.isDoor(Material.OAK_DOOR))
        assertFalse(BlockUtils.isChest(Material.STONE))
    }

    @Test
    fun entityPdc_keysUseInjectedNamespace() {
        assertEquals("sneakyutils", EntityPdc.key("Flag.Name").namespace)
        assertEquals("flag.name", EntityPdc.key("Flag.Name").key)
        assertEquals("flag_name", EntityPdc.key("Flag Name").key)
        assertEquals("magicspells", EntityPdc.key("magicspells", "altered").namespace)
    }

    @Test
    fun poseFacing_snapsAndMaps() {
        assertEquals(0f, PoseFacing.snapYaw(10f))
        assertEquals(BlockFace.SOUTH, PoseFacing.yawToBlockFace(0f))
        assertEquals(Direction.NORTH, PoseFacing.getDirection(180f))
        assertEquals(Direction.SOUTHWEST, PoseFacing.getDoubleDirection(45f))
    }

    @Test
    fun inventoryUtility_roundTripsPaperYaml() {
        val server: ServerMock = MockBukkit.mock()
        try {
            val items = arrayOf(ItemStack(Material.DIAMOND, 3), null, ItemStack(Material.STONE))
            val yaml = InventoryUtility.inventoryToPaperYaml(items)
            assertTrue(InventoryUtility.looksLikePaperYaml(yaml))
            val restored = InventoryUtility.inventoryFromPaperYaml(yaml)
            assertEquals(3, restored[0].amount)
            assertEquals(Material.DIAMOND, restored[0].type)
            assertEquals(Material.STONE, restored[2].type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun inventoryUtility_roundTripsLegacyBase64() {
        MockBukkit.mock()
        try {
            val items = arrayOf(ItemStack(Material.APPLE, 2), ItemStack(Material.AIR))
            val encoded = InventoryUtility.inventoryToBase64(items.size, items)
            val restored = InventoryUtility.getSavedInventoryStrict(encoded)
            assertArrayEquals(arrayOf(Material.APPLE, Material.AIR), restored.map { it?.type }.toTypedArray())
            assertEquals(2, restored[0].amount)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun inventoryUtility_roundTripsPaperPerItemBase64() {
        MockBukkit.mock()
        try {
            val inventory = org.bukkit.Bukkit.createInventory(null, 9)
            inventory.setItem(0, ItemStack(Material.DIAMOND, 4))
            inventory.setItem(2, ItemStack(Material.STONE, 1))
            val encoded = InventoryUtility.inventoryPaperToBase64(inventory)
            val restored = InventoryUtility.inventoryPaperFromBase64(encoded)
            assertEquals(9, restored.size)
            assertEquals(Material.DIAMOND, restored[0].type)
            assertEquals(4, restored[0].amount)
            assertEquals(Material.AIR, restored[1].type)
            assertEquals(Material.STONE, restored[2].type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun itemMetaText_setsDisplayName() {
        MockBukkit.mock()
        try {
            val stack = ItemStack(Material.PAPER)
            val meta = stack.itemMeta
            ItemMetaText.setDisplayName(meta, "&cHello")
            ItemMetaText.setLore(meta, listOf("&7line"))
            stack.itemMeta = meta
            assertTrue(meta.hasDisplayName())
            assertTrue(meta.hasLore())
        } finally {
            MockBukkit.unmock()
        }
    }
}
