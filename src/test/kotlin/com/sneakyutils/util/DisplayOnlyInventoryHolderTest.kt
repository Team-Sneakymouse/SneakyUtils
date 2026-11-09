package com.sneakyutils.util

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DisplayOnlyInventoryHolderTest {
    private class MenuHolder : DisplayOnlyInventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    private class StorageHolder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    /** MockBukkit does not implement `getHolder(false)`, so stub the inventory directly. */
    private fun inventoryHeldBy(holder: InventoryHolder?): Inventory =
        mock(Inventory::class.java).also { `when`(it.getHolder(false)).thenReturn(holder) }

    @Test
    fun `display-only holder is detected`() {
        assertTrue(DisplayOnlyInventoryHolder.isDisplayOnly(inventoryHeldBy(MenuHolder())))
    }

    @Test
    fun `ordinary holders, null holders and null inventories are not display-only`() {
        assertFalse(DisplayOnlyInventoryHolder.isDisplayOnly(inventoryHeldBy(StorageHolder())))
        assertFalse(DisplayOnlyInventoryHolder.isDisplayOnly(inventoryHeldBy(null)))
        assertFalse(DisplayOnlyInventoryHolder.isDisplayOnly(null))
    }
}
