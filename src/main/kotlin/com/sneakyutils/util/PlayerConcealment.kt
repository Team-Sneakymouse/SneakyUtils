package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Players hidden from everyone, themselves included, without spectator mode or [Player.hidePlayer]. They stay in the
 * world as real entities and stay in the tab list. Use it through [PlayerUtil.conceal] / [PlayerUtil.reveal].
 *
 * A concealed player:
 * - is invisible ([Player.setInvisible]), put back as it was when the last owner reveals them;
 * - has their held items and armour sent to other players as empty, however often the server re-sends them;
 * - sees their own armour, hotbar and offhand as empty (so nothing's in their hand in first person, or on them in
 *   third person), while the real items stay in their inventory. In Creative, clicks that would write those fake-empty
 *   slots back are cancelled;
 * - has everything riding them (worn cosmetics, nametag displays, chat bubbles; not other players) hidden from every
 *   viewer, themselves included, until it gets off or they're revealed.
 *
 * Each owner (a plugin) conceals and reveals independently: the player is revealed only when every owner has revealed
 * them, so one plugin can't undo another's. Everything comes off on quit and when SneakyUtils disables. All calls on
 * the main thread.
 */
class PlayerConcealment internal constructor(private val plugin: Plugin) : Listener {

    private val owners = HashMap<UUID, LinkedHashSet<String>>()
    private val wasInvisible = HashMap<UUID, Boolean>()

    /** Concealed player → each rider hidden for them → the viewers it's hidden from. */
    private val riders = HashMap<UUID, HashMap<UUID, HashSet<UUID>>>()

    private val packets = ConcealPackets(plugin)
    private var task: BukkitTask? = null

    internal fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        for (p in Bukkit.getOnlinePlayers()) packets.inject(p)
    }

    internal fun shutdown() {
        task?.cancel()
        task = null
        for (id in owners.keys.toList()) {
            owners.remove(id)
            Bukkit.getPlayer(id)?.let { restore(it) } ?: forget(id)
        }
        for (p in Bukkit.getOnlinePlayers()) packets.eject(p)
        HandlerList.unregisterAll(this)
    }

    /** Conceals p for owner (no change if owner already conceals them). */
    fun conceal(owner: Plugin, p: Player) {
        val set = owners.getOrPut(p.uniqueId) { LinkedHashSet() }
        val first = set.isEmpty()
        if (!set.add(owner.name) || !first) return
        wasInvisible[p.uniqueId] = p.isInvisible
        p.isInvisible = true
        packets.conceal(p)
        hideRiders(p)
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    /** owner stops concealing p; they're revealed once no owner conceals them. */
    fun reveal(owner: Plugin, p: Player) {
        val set = owners[p.uniqueId] ?: return
        if (!set.remove(owner.name) || set.isNotEmpty()) return
        owners.remove(p.uniqueId)
        restore(p)
    }

    fun isConcealed(p: Player): Boolean = owners.containsKey(p.uniqueId)

    /** The plugins concealing p, by name (empty if none). */
    fun owners(p: Player): Set<String> = owners[p.uniqueId]?.toSet() ?: emptySet()

    private fun restore(p: Player) {
        p.isInvisible = wasInvisible.remove(p.uniqueId) ?: false
        packets.reveal(p)
        showRiders(p.uniqueId)
    }

    private fun forget(id: UUID) {
        wasInvisible.remove(id)
        packets.forget(id)
        showRiders(id)
    }

    /** Every tick while anyone's concealed. */
    internal fun tick() {
        if (owners.isEmpty()) {
            task?.cancel()
            task = null
            return
        }
        for (id in owners.keys) {
            val p = Bukkit.getPlayer(id) ?: continue
            if (!p.isInvisible) p.isInvisible = true                  // something else made them visible
            packets.track(p)
            hideRiders(p)
        }
    }

    // ------------------------------------------------------------------ riders

    private fun hideRiders(p: Player) {
        val now = HashMap<UUID, Entity>()
        collectRiders(p, now)
        val hidden = riders.getOrPut(p.uniqueId) { HashMap() }
        val off = hidden.keys.filter { it !in now }
        for (id in off) show(id, hidden.remove(id)!!)
        if (now.isEmpty()) return
        val viewers = Bukkit.getOnlinePlayers()
        for ((id, r) in now) {
            val from = hidden.getOrPut(id) { HashSet() }
            for (v in viewers) if (from.add(v.uniqueId)) v.hideEntity(plugin, r)
        }
    }

    private fun showRiders(player: UUID) {
        val hidden = riders.remove(player) ?: return
        for ((id, from) in hidden) show(id, from)
    }

    private fun show(rider: UUID, from: Set<UUID>) {
        val r = Bukkit.getEntity(rider) ?: return
        for (v in from) Bukkit.getPlayer(v)?.showEntity(plugin, r)
    }

    private fun collectRiders(e: Entity, into: MutableMap<UUID, Entity>) {
        for (r in e.passengers) {
            if (r is Player) continue
            into[r.uniqueId] = r
            collectRiders(r, into)
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(e: PlayerJoinEvent) {
        packets.inject(e.player)
    }

    /** After everyone else's quit handlers (owners reveal there): whatever's left comes off. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) {
        val p = e.player
        if (owners.remove(p.uniqueId) != null) {
            p.isInvisible = wasInvisible.remove(p.uniqueId) ?: false
            packets.forget(p.uniqueId)
            showRiders(p.uniqueId)
        }
        // they're a viewer no longer: their hidden-entity list goes with them, and is rebuilt if they come back
        for (m in riders.values) for (from in m.values) from.remove(p.uniqueId)
    }

    /** Other menus send their real hotbar: send the masked inventory again once one closes. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        if (!packets.masksSelf(p.uniqueId)) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (p.isOnline && packets.masksSelf(p.uniqueId)) p.updateInventory()
        })
    }

    /** In Creative the client writes slots itself: don't let it write a masked (fake-empty) slot over a real item. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onCreative(e: InventoryCreativeEvent) {
        val p = e.whoClicked as? Player ?: return
        if (packets.masksSelf(p.uniqueId) && ConcealPackets.menuSlotMasked(e.rawSlot)) e.isCancelled = true
    }
}
