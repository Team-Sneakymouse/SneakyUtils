package com.sneakyutils.watch;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** Cancels interactions/damage/etc for one engine's watchers (registered by {@link WatchEngine#register()}). */
public final class WatchRestrictions implements Listener {

    private final WatchEngine<?> engine;

    WatchRestrictions(WatchEngine<?> engine) {
        this.engine = engine;
    }

    // 1.8.0: an adventure-mode watcher can't touch anything (spectators couldn't either)

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherInteract(PlayerInteractEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherInteractEntity(PlayerInteractEntityEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherAttack(PrePlayerAttackEntityEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        if (engine.watchingHere(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        e.setCancelled(true);
    }

    // the rest of a watcher's own inputs (MagicSpells' triggers): a spell cast right after one of these is theirs

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (engine.watchingHere(e.getPlayer())) engine.input(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (!engine.watchingHere(e.getPlayer())) return;
        engine.input(e.getPlayer());
        engine.hideGearSoon(e.getPlayer());         // what they hold now would show
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (engine.watchingHere(e.getPlayer())) engine.input(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWatcherArmour(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent e) {
        if (engine.watchingHere(e.getPlayer())) engine.hideGearSoon(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWatcherInventory(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && engine.watchingHere(p)) engine.hideGearSoon(p);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (engine.watchingHere(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherExp(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent e) {
        if (engine.watchingHere(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (engine.watchingHere(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherBreak(BlockBreakEvent e) {
        if (engine.watchingHere(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherConsume(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        if (engine.watchingHere(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (engine.watchingHere(e.getEntity().getShooter() instanceof Entity en ? en : null)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherHurt(EntityDamageEvent e) {
        if (engine.watchingHere(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherTargeted(org.bukkit.event.entity.EntityTargetEvent e) {
        if (engine.watchingHere(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWatcherHunger(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (engine.watchingHere(e.getEntity())) e.setCancelled(true);
    }

}
