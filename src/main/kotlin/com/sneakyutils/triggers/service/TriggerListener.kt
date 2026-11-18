package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.ClickSide
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.event.world.WorldUnloadEvent

/**
 * The trigger service's one listener per event type. Movement handlers observe at MONITOR and skip cancelled events,
 * so they see where the player really ends up and never change the outcome.
 *
 * Not used: Paper's `EntityMoveEvent` (it fires for every living entity on the server); riders are covered by the
 * mounted poll instead.
 */
internal class TriggerListener(private val service: TriggerService) : Listener {
    private companion object {
        const val DIAGNOSTICS_SERVICE = "com.teamsneakymouse.sneakydiagnostics.api.Diagnostics"
    }

    private val core = service.core

    /** The hot path. The first check is the in-place block compare; everything else is behind it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        service.moveEvents++
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val world = to.world ?: return
        service.moved(event.player.uniqueId, world.uid, to.x, to.y, to.z)
    }

    /**
     * Paper also reports every dismount as a teleport (cause DISMOUNT, from the seat to the spot beside the vehicle,
     * measured on Paper 26.2): that is a step on foot, not a teleport.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        service.teleportEvents++
        val to = event.to
        val world = to.world ?: return
        service.teleported(event.player, world.uid, to.x, to.y, to.z, event.cause == PlayerTeleportEvent.TeleportCause.DISMOUNT)
    }

    /** Fired before the mount happens (and cancelled ones are skipped): the service applies it next tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        val p = event.entity as? Player ?: return
        service.ridingEvent(p)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val p = event.entity as? Player ?: return
        service.ridingEvent(p)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        service.playerJoined(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        service.playerQuit(event.player)
    }

    /** The player is already in the new world here; a cross-world teleport usually reported it first (then a no-op). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        service.worldChanged(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        service.died(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val loc = event.respawnLocation
        val world = loc.world ?: return
        service.respawned(event.player, world.uid, loc.x, loc.y, loc.z)
    }

    /**
     * The event comes before the mode changes: the proposed mode is applied here, and checked against the player's
     * real mode on the next tick ([TriggerService.gameModeEvent]), because a handler called from here may change the
     * mode again (the outer change then wins) and a later listener may still cancel. Cancelled changes don't get here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        val s = core.state(event.player.uniqueId) ?: return
        service.gameModeEvent(event.player)
        service.stage(TriggerService.STAGE_PLAYER, "player-state") {
            val spectator = event.newGameMode == GameMode.SPECTATOR
            service.refineForShapes(s) // shaped occupancies are tested where the player is now
            // Into or out of spectator: the full refresh. Any other change (survival to creative): shaped occupancies
            // only; areas hear nothing of it, as before.
            if (s.spectator != spectator) core.setSpectator(s, spectator) else core.retestShapedOccupancies(s)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== service.plugin) service.closeOwner(event.plugin)
    }

    // SneakyDiagnostics' service, recognised by class name (naming the class would load it). MONITOR: after the shim
    // (integration/Probe, NORMAL) attached or dropped its probe.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceUp(event: ServiceRegisterEvent) {
        if (event.provider.service.name == DIAGNOSTICS_SERVICE) service.diagnosticsChanged(true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceDown(event: ServiceUnregisterEvent) {
        if (event.provider.service.name == DIAGNOSTICS_SERVICE) service.diagnosticsChanged(false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        service.worldUnloaded(event.world.uid)
    }

    // ---- clicks. HIGH: after protection plugins (so ignoreCancelled sees their decision), before MONITOR observers
    // (so a handler's cancel counts). Not ignoreCancelled: each subscription decides with ClickOptions.ignoreCancelled.

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        val clicks = service.clicks
        // Nothing subscribed and no remembered main-hand decision this tick: nothing to do.
        if (clicks.clickSubs == 0 && !clicks.decisionPending()) return
        clicks.interactEvents++
        val side = when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> ClickSide.RIGHT
            Action.LEFT_CLICK_BLOCK -> ClickSide.LEFT
            Action.PHYSICAL -> ClickSide.PHYSICAL
            else -> return
        }
        val block = event.clickedBlock ?: return
        clicks.interact(event, block, side)
    }

    /** Not ignoreCancelled: subscriptions with BreakOptions.ignoreCancelled off want cancelled breaks too. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onBreak(event: BlockBreakEvent) {
        val clicks = service.clicks
        if (clicks.breakSubs == 0) return
        clicks.breakEvents++
        clicks.broke(event)
    }

    // An entity right click arrives as PlayerInteractAtEntityEvent, PlayerInteractEntityEvent, or both: the client
    // sends "interact at" first and plain "interact" only when the first was not consumed (armor stands consume it).
    // PlayerInteractAtEntityEvent has no HandlerList of its own, so this one handler receives both kinds.
    // Each click is dispatched once: after an "at" event the player's slot for that hand remembers it, and the plain
    // event that follows for the same player, entity and hand in the same tick is the same click. It is not dispatched
    // again; it gets the "at" event's cancel decision instead, and the slot is used up (see ClickIndex.rememberAt).
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val clicks = service.clicks
        // Pairing and the off-hand half still apply when a handler closed the last entity subscription.
        if (clicks.entitySubs == 0 && !clicks.decisionPending()) return
        val player = event.player
        val entity = event.rightClicked
        val tick = service.plugin.server.currentTick
        if (event is PlayerInteractAtEntityEvent) {
            clicks.entityEvents++
            val cancelled = clicks.entity(event, player, entity, ClickSide.RIGHT, event.hand)
            clicks.rememberAt(player.uniqueId, entity.entityId, event.hand, tick, cancelled)
            return
        }
        when (clicks.consumeAt(player.uniqueId, entity.entityId, event.hand, tick)) {
            ClickIndex.PAIRED_CANCELLED -> {
                event.isCancelled = true
                return
            }
            ClickIndex.PAIRED -> return
        }
        clicks.entityEvents++
        clicks.entity(event, player, entity, ClickSide.RIGHT, event.hand)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onAttackEntity(event: PrePlayerAttackEntityEvent) {
        val clicks = service.clicks
        if (clicks.entitySubs == 0) return
        clicks.entityEvents++
        clicks.entity(event, event.player, event.attacked, ClickSide.LEFT, EquipmentSlot.HAND)
    }
}
