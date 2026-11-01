package com.sneakyutils.watch;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Someone looking through a camera (any {@link WatchEngine}'s feed). */
public final class WatchSession {
    public final UUID player;
    /** the feed being watched (its id) */
    public String camera;
    public final Location back;
    public final GameMode mode;
    public final boolean allowFlight, flying;
    public final float flySpeed;
    public UUID body;
    /** 1.5.0: the "z Z z" over the body (it's asleep: they're away watching), and its frame */
    public UUID zzz;
    /** 1.8.1: whatever rode on the player (worn cosmetics such as hats), moved onto the body while they watch */
    public final List<UUID> riders = new ArrayList<>();
    public int zzzFrame;
    public World ticketWorld;
    public int ticketX, ticketZ;
    public boolean ending;
    /** 1.3.0: the camera's signal was lost: "no signal" static until this tick, held at lostAt; then back */
    public long lostUntil = -1;
    public Location lostAt;
    /** the left/right keys as last pressed, and when the camera was last switched (ticks) */
    public boolean left, right;
    public long switched;
    /** 1.4.0: an overlay (static or paint) was last put on their screen */
    public boolean overlaid;
    /** 1.8.0: watching in adventure mode (not spectator), and what that changed: to put back on leaving */
    public boolean adventure;
    public final boolean invisible, collidable;
    public final int heldSlot;

    public WatchSession(Player p) {
        player = p.getUniqueId();
        back = p.getLocation().clone();
        mode = p.getGameMode();
        allowFlight = p.getAllowFlight();
        flying = p.isFlying();
        flySpeed = p.getFlySpeed();
        invisible = p.isInvisible();
        collidable = p.isCollidable();
        heldSlot = p.getInventory().getHeldItemSlot();
    }
}
