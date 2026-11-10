package com.sneakyutils.util

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Marks an inventory as a display-only GUI: its items are icons the plugin built for the
 * menu, not items a player owns. Plugins that scan a whole inventory when it opens
 * (SneakyItems' magic-item updater and expiration) skip these inventories. Per-item
 * handling such as click events still runs, so the owning plugin must cancel any
 * transfer that would put a real item into a GUI slot.
 *
 * Implement it on the custom holder you pass to `Bukkit.createInventory`. Prefer Paper
 * `MenuType.create` for location-free views when no custom holder is needed (see MagicsSpells
 * MenuSpell + `SneakyItemsAPI.registerMenuInventorySkip`). Never implement it on a holder
 * whose inventory stores real items (vaults, bags, backpacks).
 */
interface DisplayOnlyInventoryHolder : InventoryHolder {
    companion object {
        /** True when [inventory]'s holder is a [DisplayOnlyInventoryHolder]. Skips block-state snapshots. */
        @JvmStatic
        fun isDisplayOnly(inventory: Inventory?): Boolean =
            inventory?.getHolder(false) is DisplayOnlyInventoryHolder
    }
}
