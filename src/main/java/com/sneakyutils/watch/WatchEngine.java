package com.sneakyutils.watch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.sneakyutils.util.ColorUtil;
import com.sneakyutils.util.PlayerUtil;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import static com.sneakyutils.util.Directions.clampToCone;
import static com.sneakyutils.util.Directions.dir;
import static com.sneakyutils.util.Directions.pitchOf;
import static com.sneakyutils.util.Directions.wrap;
import static com.sneakyutils.util.Directions.yawOf;

/**
 * Looking through a camera (moved here from SneakyCCTV so other plugins' cameras share it): the watcher is held with
 * their eye at the feed's lens, looking about within a cone, while a mannequin in their skin and clothes stands where
 * they were. Sneak to come back; hitting the body, a teleport from elsewhere, leaving the game or the plugin stopping
 * also bring them back. Watching is in adventure mode (shrunk, invisible, touching nothing; {@link WatchRestrictions})
 * or, with {@code watch-mode: spectator}, in spectator mode. Who is watching is kept in a sessions file until they stop,
 * so a crash mid-watch puts them back where they stood when they next join.
 *
 * <p>One engine per kind of camera. A plugin subclasses this with its feed type F, says how to find a feed and where
 * its lens is, and adds what's its own through the hooks ({@link #entered}, {@link #ticked}, {@link #leaving},
 * {@link #left}). It calls {@link #register()} on enable, {@link #tickSessions()} and {@link #tickZzz()} each tick
 * (while anyone watches), and {@link #shutdown()} on disable. A player watches through one engine at a time.
 *
 * <p>Settings (from {@link #settings()}): {@code watch-mode}, {@code body-name}, {@code sleeping-body.*},
 * {@code forward-damage}, {@code hide-watcher-gear}, {@code empty-hand}, {@code switch-keys}.
 *
 * <p>Anything that starts or ends a watch must keep the sessions file current and restore game mode, body, hidden gear
 * and the hooks' own changes, including the quit, death, disable and feed-removal paths.
 */
public abstract class WatchEngine<F> implements Listener {

    /** a standing player's eye height at scale 1 */
    public static final double STANDING_EYE = 1.62;

    private static final List<WatchEngine<?>> ENGINES = new CopyOnWriteArrayList<>();

    private static final EquipmentSlot[] WORN = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    protected final JavaPlugin plugin;
    private final String sessionsFile;
    private final NamespacedKey bodyKey, shrinkKey;
    private final Map<UUID, WatchSession> sessions = new HashMap<>();
    /** when each watcher last clicked or pressed something (ticks) */
    private final Map<UUID, Long> lastInput = new HashMap<>();
    /** who has sneak held, from the toggle events */
    private final Set<UUID> sneakers = new HashSet<>();
    private final WatchRestrictions restrictions = new WatchRestrictions(this);
    /** set while this engine moves a watcher, so onTeleport knows it's ours */
    private boolean moving;

    /**
     * sessionsFile: in the plugin's data folder. The body's tag is {@code <plugin>:body} and the shrinking modifier's
     * {@code <plugin>:watching}.
     */
    protected WatchEngine(JavaPlugin plugin, String sessionsFile) {
        this.plugin = plugin;
        this.sessionsFile = sessionsFile;
        this.bodyKey = new NamespacedKey(plugin, "body");
        this.shrinkKey = new NamespacedKey(plugin, "watching");
    }

    // ------------------------------------------------------------------ what a camera plugin says

    /** The feed with this id, or null if it's gone (anyone watching it is sent back). */
    protected abstract F feed(String id);

    protected abstract String feedId(F f);

    /** Where the watcher's eye goes, or null if it can't be watched now. */
    protected abstract Location eye(F f);

    /** Straight out from what the camera's on (a unit vector): the middle of the cone they can look round. */
    protected abstract Vector normal(F f);

    /** How far from {@link #normal} they can look, in degrees (180: anywhere). */
    protected abstract double limit(F f);

    /** Where they look on arriving. */
    protected abstract float yaw(F f);

    protected abstract float pitch(F f);

    /** The settings above: a config section. */
    protected abstract ConfigurationSection settings();

    /** The feeds the left/right keys go through from this one, in order (fewer than 2: the keys do nothing). */
    protected List<F> switchOrder(Player p, F current) {
        return List.of();
    }

    /** The feed's own entities, hidden from its watchers (they'd sit in front of the lens). */
    protected Collection<Entity> feedEntities(F f) {
        return List.of();
    }

    /** Whether p may start watching now (only asked when they aren't already). */
    protected boolean canWatch(Player p) {
        return true;
    }

    /** Arrived at a feed: first = they've only just started watching (else they switched). */
    protected void entered(Player p, WatchSession s, F f, boolean first) {}

    /** Every tick while watching f (not while their signal is lost), looking along yaw/pitch. */
    protected void ticked(Player p, WatchSession s, F f, float yaw, float pitch) {}

    /** Stopping, before they're put back: f is null if it's gone. */
    protected void leaving(Player p, WatchSession s, F f) {}

    /** Stopped and back. */
    protected void left(Player p, WatchSession s) {}

    /** A message: key and its default, legacy &-colours. */
    protected String msg(String key, String def) {
        String c = ColorUtil.colorize(def);
        return c != null ? c : def;
    }

    /** The name over the body: their character's. */
    protected String bodyName(Player p) {
        return PlayerUtil.getCharacterName(p);
    }

    /** The skin ({texture, signature}) for the body when the player isn't showing one, or null. */
    protected String[] fallbackSkin(Player p) {
        return null;
    }

    /** The tick clock (lostUntil, lastInput, switching). */
    public long now() {
        return Bukkit.getCurrentTick();
    }

    /** Timing hooks around the move handler (for a plugin's own profiler). */
    protected long probeStart() {
        return 0;
    }

    protected void probeEnd(String what, long start) {}

    // ------------------------------------------------------------------ lifecycle

    /** Listens (this and the watcher restrictions) and joins the engines a player can watch through. */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(restrictions, plugin);
        ENGINES.add(this);
    }

    /** Everyone watching comes back; the listeners go. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leave(p, null, true);
        }
        ENGINES.remove(this);
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(restrictions);
    }

    /** Whether p is watching through any engine. */
    public static boolean watchingAny(Player p) {
        for (WatchEngine<?> e : ENGINES) if (e.watchingHere(p)) return true;
        return false;
    }

    public JavaPlugin plugin() { return plugin; }
    public NamespacedKey bodyKey() { return bodyKey; }
    public NamespacedKey shrinkKey() { return shrinkKey; }
    public Map<UUID, WatchSession> sessions() { return sessions; }
    public Map<UUID, Long> lastInput() { return lastInput; }
    public Set<UUID> sneakers() { return sneakers; }

    // ------------------------------------------------------------------ watching

    public void tickSessions() {
        for (WatchSession s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) continue;
            if (s.lostUntil >= 0) {                                  // the static, then back
                if (now() >= s.lostUntil) {
                    leave(p, msg("signal-lost", "&cSignal lost."), true);
                } else if (s.lostAt != null && (p.getWorld() != s.lostAt.getWorld() || p.getLocation().distanceSquared(s.lostAt) > 0.0025)) {
                    Location hold = s.lostAt.clone();
                    hold.setYaw(p.getLocation().getYaw());
                    hold.setPitch(p.getLocation().getPitch());
                    move(p, hold);
                }
                continue;
            }
            F f = feed(s.camera);
            Location eye = f == null ? null : eye(f);
            if (eye == null) {
                leave(p, msg("signal-lost", "&cSignal lost."), true);
                continue;
            }
            Location l = p.getLocation();
            Vector d = clampToCone(dir(l.getYaw(), l.getPitch()), normal(f), limit(f));
            float yaw = yawOf(d), pitch = pitchOf(d);
            boolean clamped = Math.abs(wrap(yaw - l.getYaw())) > 0.5 || Math.abs(pitch - l.getPitch()) > 0.5;
            Location want = stand(p, eye, l.getYaw(), l.getPitch());
            if (l.getWorld() != eye.getWorld() || l.distanceSquared(want) > 0.0025) {
                move(p, stand(p, eye, yaw, pitch));
            } else if (clamped) {
                p.setRotation(yaw, pitch);
            }
            if (now() % 20 == 0) hideFeed(p, f);                     // its entities may have loaded after they arrived
            ticked(p, s, f, yaw, pitch);
        }
    }

    /**
     * Where to put the player so their eye is at the camera's eye. A shrunk watcher's eye height is worked out from the
     * scale they have now (the server only resizes them on their next tick).
     */
    public Location stand(Player p, Location eye, float yaw, float pitch) {
        double h = p.getEyeHeight();
        org.bukkit.attribute.AttributeInstance a = p.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (a != null && a.getModifier(shrinkKey) != null) h = STANDING_EYE * a.getValue();
        Location l = eye.clone().subtract(0, h, 0);
        l.setYaw(yaw);
        l.setPitch(pitch);
        return l;
    }

    /** Starts watching f, or switches to it if they already watch through this engine. */
    public void watch(Player p, F f) {
        Location eye = eye(f);
        if (eye == null) {
            p.sendActionBar(msg("signal-lost", "&cSignal lost."));
            return;
        }
        WatchSession s = sessions.get(p.getUniqueId());
        boolean first = s == null;
        if (first) {
            if (p.isInsideVehicle() || p.isDead() || watchingAny(p) || !canWatch(p)) {
                p.sendActionBar(msg("cant-now", "&7You can't watch cameras right now."));
                return;
            }
            s = new WatchSession(p);
            sessions.put(p.getUniqueId(), s);
            hideGearSoon(p);
            leaveBody(p, s);
            saveSessions();
            if (adventureWatching()) {
                startAdventure(p, s);
            } else {
                p.setGameMode(GameMode.SPECTATOR);
                p.setFlySpeed(0f);
            }
        } else if (s.camera != null) {
            F old = feed(s.camera);
            if (old != null) showFeed(p, old);
        }
        s.camera = feedId(f);
        hideFeed(p, f);
        move(p, stand(p, eye, yaw(f), pitch(f)));
        entered(p, s, f, first);
    }

    /**
     * A mannequin wearing the player's skin and clothes stands where they were: their armour, hat and both hands, the
     * skin they're showing right now (a uniform too, not only their character's), and anything riding on them (worn
     * cosmetics) moved onto the body until they come back.
     */
    private void leaveBody(Player p, WatchSession s) {
        ConfigurationSection cfg = settings();
        Location at = p.getLocation();
        boolean asleep = cfg.getBoolean("sleeping-body.enabled", true);
        if (asleep) at.setPitch((float) Math.max(-90, Math.min(90, cfg.getDouble("sleeping-body.pitch", 90))));
        Entity m = spawnBody(p, at);
        m.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, p.getUniqueId().toString());
        s.body = m.getUniqueId();
        for (Entity r : new ArrayList<>(p.getPassengers())) {          // worn cosmetics ride on the body
            if (r instanceof Player) continue;
            p.removePassenger(r);
            if (m.addPassenger(r)) s.riders.add(r.getUniqueId());
            else p.addPassenger(r);
        }
        if (asleep) {
            m.setRotation(at.getYaw(), at.getPitch());                  // its head hangs: looking straight down
            if (cfg.getBoolean("sleeping-body.zzz", true)) s.zzz = spawnZzz(p, m.getLocation()).getUniqueId();
        }
        s.ticketWorld = at.getWorld();
        s.ticketX = at.getBlockX() >> 4;
        s.ticketZ = at.getBlockZ() >> 4;
        s.ticketWorld.addPluginChunkTicket(s.ticketX, s.ticketZ, plugin);
    }

    /** The body itself: a mannequin in their skin (as shown now), clothes and hands, with their name over it. */
    protected Entity spawnBody(Player p, Location at) {
        PlayerProfile profile = Bukkit.createProfile(p.getUniqueId(), p.getName());
        String[] skin = shownSkin(p);
        if (skin == null) skin = fallbackSkin(p);
        if (skin != null) profile.setProperty(new ProfileProperty("textures", skin[0], skin[1]));
        String name = bodyName(p);
        boolean named = settings().getBoolean("body-name", true);
        return at.getWorld().spawn(at, Mannequin.class, b -> {
            b.setProfile(ResolvableProfile.resolvableProfile(profile));
            b.setDescription(null);
            if (named) {
                b.customName(Component.text(name));
                b.setCustomNameVisible(true);
            }
            b.setImmovable(true);
            b.setPersistent(false);
            EntityEquipment to = b.getEquipment(), from = p.getEquipment();
            to.setHelmet(from.getHelmet());
            to.setChestplate(from.getChestplate());
            to.setLeggings(from.getLeggings());
            to.setBoots(from.getBoots());
            to.setItemInMainHand(from.getItemInMainHand());
            to.setItemInOffHand(from.getItemInOffHand());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    to.setDropChance(slot, 0f);
                } catch (IllegalArgumentException ignored) {
                    // a slot mannequins don't have
                }
            }
        });
    }

    /** The skin others see on the player now ({texture, signature}), or null if they show none. */
    public static String[] shownSkin(Player p) {
        for (ProfileProperty prop : p.getPlayerProfile().getProperties())
            if ("textures".equals(prop.getName()) && prop.getValue() != null && !prop.getValue().isBlank())
                return new String[] {prop.getValue(), prop.getSignature()};
        return null;
    }

    /** Whether e is a watcher's body (or its "z Z z") left by this engine. */
    public boolean isBodyPart(Entity e) {
        return e.getPersistentDataContainer().has(bodyKey, PersistentDataType.STRING);
    }

    /**
     * The "z Z z" floating over a watcher's body, so anyone nearby (or hearing them on voice chat) can see they're
     * away: a text display over its name (sleeping-body.height, or height-no-name with body-name off), the frames of
     * sleeping-body.frames shown in turn every frame-ticks, bobbing gently. Never saved.
     */
    private TextDisplay spawnZzz(Player p, Location body) {
        ConfigurationSection cfg = settings();
        boolean named = cfg.getBoolean("body-name", true);
        double up = named ? cfg.getDouble("sleeping-body.height", 2.55) : cfg.getDouble("sleeping-body.height-no-name", 2.15);
        float scale = (float) cfg.getDouble("sleeping-body.scale", 1.0);
        Location at = body.clone().add(0, up, 0);
        at.setPitch(0);
        return at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            t.setSeeThrough(false);
            t.text(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(zzzFrame(0)));
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(scale), new org.joml.Quaternionf()));
            t.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, p.getUniqueId().toString());
        });
    }

    private String zzzFrame(int k) {
        List<String> frames = settings().getStringList("sleeping-body.frames");
        if (frames.isEmpty()) frames = List.of("&7z", "&7z &fZ", "&7z &fZ &7z", "&fZ &7z", "&7z", "");
        String colored = ColorUtil.colorize(frames.get(Math.floorMod(k, frames.size())));
        return colored != null ? colored : "";
    }

    /** Every frame-ticks: each body's zzz on to its next frame, and a little up or back down. */
    public void tickZzz() {
        ConfigurationSection cfg = settings();
        int every = Math.max(1, cfg.getInt("sleeping-body.frame-ticks", 10));
        if (now() % every != 0) return;
        float scale = (float) cfg.getDouble("sleeping-body.scale", 1.0);
        for (WatchSession s : sessions.values()) {
            if (s.zzz == null) continue;
            if (!(Bukkit.getEntity(s.zzz) instanceof TextDisplay t) || !t.isValid()) continue;
            int k = ++s.zzzFrame;
            t.text(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(zzzFrame(k)));
            t.setInterpolationDelay(0);
            t.setInterpolationDuration(every);
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, k % 2 == 0 ? 0f : 0.12f, 0),
                new org.joml.Quaternionf(), new org.joml.Vector3f(scale), new org.joml.Quaternionf()));
        }
    }

    public void hideFeed(Player p, F f) {
        for (Entity e : feedEntities(f)) p.hideEntity(plugin, e);
    }

    public void showFeed(Player p, F f) {
        for (Entity e : feedEntities(f)) p.showEntity(plugin, e);
    }

    /**
     * Stops watching: back into their body (unless toBody is false: they're being taken somewhere else), their game
     * mode back, the body gone. why: a line for their action bar, or null.
     */
    public void leave(Player p, String why, boolean toBody) {
        WatchSession s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        F f = s.camera == null ? null : feed(s.camera);
        if (f != null) showFeed(p, f);
        leaving(p, s, f);
        Entity body = s.body == null ? null : Bukkit.getEntity(s.body);
        Location back = body != null && body.isValid() ? body.getLocation() : s.back;
        List<Entity> riders = new ArrayList<>();                      // back off the body, onto them
        for (UUID id : s.riders) {
            Entity r = Bukkit.getEntity(id);
            if (r == null || !r.isValid()) continue;
            if (body != null) body.removePassenger(r);
            riders.add(r);
        }
        if (body != null) body.remove();
        Entity zzz = s.zzz == null ? null : Bukkit.getEntity(s.zzz);
        if (zzz != null) zzz.remove();
        if (s.ticketWorld != null) s.ticketWorld.removePluginChunkTicket(s.ticketX, s.ticketZ, plugin);
        if (s.adventure) endAdventure(p, s);
        lastInput.remove(p.getUniqueId());
        p.setGameMode(s.mode);
        p.setFlySpeed(s.flySpeed);
        p.setAllowFlight(s.allowFlight);
        if (toBody) {
            back.setYaw(s.back.getYaw());
            back.setPitch(s.back.getPitch());
            move(p, back);
        }
        if (!riders.isEmpty()) remount(p, riders, 0);
        if (s.allowFlight) p.setFlying(s.flying);
        if (hidesGear()) {
            showGear(p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {        // and again once they've arrived (anyone new in range)
                if (!watchingHere(p)) showGear(p);
            }, 2L);
        }
        p.setFallDistance(0);
        saveSessions();
        if (why != null) p.sendActionBar(why);
        left(p, s);
    }

    /** Everyone watching this feed comes back (why: their action bar line, or null). */
    public void endFor(String feedId, String why) {
        for (WatchSession s : new ArrayList<>(sessions.values())) {
            if (!feedId.equals(s.camera)) continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null) leave(p, why, true);
        }
    }

    /**
     * What rode on a watcher goes back on them. A player who's just been moved can't take passengers until the move
     * has gone through, so whatever doesn't get on is tried again each tick, for up to 20 ticks.
     */
    private void remount(Player p, List<Entity> riders, int tries) {
        List<Entity> left = new ArrayList<>();
        for (Entity r : riders) {
            if (!p.isValid() || !r.isValid() || r.getVehicle() != null) continue;
            if (r.getWorld() != p.getWorld()) r.teleport(p.getLocation());
            if (!p.addPassenger(r)) left.add(r);
        }
        if (!left.isEmpty() && tries < 20) Bukkit.getScheduler().runTaskLater(plugin, () -> remount(p, left, tries + 1), 1L);
    }

    private boolean adventureWatching() {
        return !"spectator".equalsIgnoreCase(settings().getString("watch-mode", "adventure"));
    }

    /**
     * Watching without spectator mode, so there are no spectator controls and invisible entities stay invisible to
     * them: adventure mode, flying at no speed, invisible, not pushing or pushed, shrunk to the smallest scale (so their
     * eye can sit at the camera without the rest of them in a wall or floor), and their hand on an empty hotbar slot if
     * there's one. {@link WatchRestrictions} keeps them from touching anything meanwhile.
     */
    private void startAdventure(Player p, WatchSession s) {
        s.adventure = true;
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFlySpeed(0f);
        p.setInvisible(true);
        p.setCollidable(false);
        p.setFireTicks(0);
        shrink(p, true);
        if (settings().getBoolean("empty-hand", true)) {
            var inv = p.getInventory();
            ItemStack held = inv.getItem(inv.getHeldItemSlot());
            if (held != null && !held.isEmpty()) {
                for (int i = 0; i < 9; i++) {
                    ItemStack it = inv.getItem(i);
                    if (it == null || it.isEmpty()) {
                        inv.setHeldItemSlot(i);
                        break;
                    }
                }
            }
        }
    }

    private void endAdventure(Player p, WatchSession s) {
        shrink(p, false);
        p.setInvisible(s.invisible);
        p.setCollidable(s.collidable);
        if (s.heldSlot >= 0 && s.heldSlot < 9) p.getInventory().setHeldItemSlot(s.heldSlot);
    }

    /** The smallest scale while watching (an attribute modifier of the plugin's, so it's easy to take off again). */
    public void shrink(Player p, boolean on) {
        org.bukkit.attribute.AttributeInstance a = p.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (a == null) return;
        for (org.bukkit.attribute.AttributeModifier m : new ArrayList<>(a.getModifiers()))
            if (shrinkKey.equals(m.getKey())) a.removeModifier(m);
        if (on) a.addModifier(new org.bukkit.attribute.AttributeModifier(shrinkKey, -0.9375,
            org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, org.bukkit.inventory.EquipmentSlotGroup.ANY));
    }

    public boolean watchingHere(Entity e) {
        return e instanceof Player p && sessions.containsKey(p.getUniqueId());
    }

    public void input(Player p) {
        lastInput.put(p.getUniqueId(), now());
    }

    // a watcher's held items and armour, hidden from everyone else (they'd float at the camera, where anyone
    // else watching it sees them in front of their eyes)

    private boolean hidesGear() {
        return settings().getBoolean("hide-watcher-gear", true);
    }

    /** Sends everyone else (in their world) nothing in p's hands or on them. */
    public void hideGear(Player p) {
        if (!hidesGear() || !p.isValid()) return;
        Map<EquipmentSlot, ItemStack> none = new java.util.EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : WORN) none.put(slot, ItemStack.empty());
        for (Player v : p.getWorld().getPlayers()) if (v != p) v.sendEquipmentChange(p, none);
    }

    /** hideGear on the next tick (after the server has sent the change it's reacting to). */
    public void hideGearSoon(Player p) {
        if (hidesGear()) Bukkit.getScheduler().runTask(plugin, () -> {
            if (watchingHere(p)) hideGear(p);
        });
    }

    /** Sends everyone else what p really holds and wears again. */
    public void showGear(Player p) {
        if (!p.isValid()) return;
        Map<EquipmentSlot, ItemStack> worn = new java.util.EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : WORN) {
            ItemStack it = p.getInventory().getItem(slot);
            worn.put(slot, it == null ? ItemStack.empty() : it);
        }
        for (Player v : p.getWorld().getPlayers()) if (v != p) v.sendEquipmentChange(p, worn);
    }

    public boolean watched(String feedId) {
        for (WatchSession s : sessions.values()) if (feedId.equals(s.camera)) return true;
        return false;
    }

    /** Whether the player is holding sneak (the toggle events, as isSneaking() can lag behind them). */
    public boolean sneaking(Player p) {
        return p.isSneaking() || sneakers.contains(p.getUniqueId());
    }

    /** Left/right while watching: the previous/next feed in {@link #switchOrder}. */
    @EventHandler
    public void onInput(PlayerInputEvent e) {
        Player p = e.getPlayer();
        WatchSession s = sessions.get(p.getUniqueId());
        if (s == null || s.lostUntil >= 0) return;
        Input in = e.getInput();
        boolean left = in.isLeft(), right = in.isRight();
        boolean pressedLeft = left && !s.left, pressedRight = right && !s.right;
        s.left = left;
        s.right = right;
        if (!settings().getBoolean("switch-keys", true) || pressedLeft == pressedRight || now() - s.switched < 4) return;
        F current = feed(s.camera);
        if (current == null) return;
        List<F> list = switchOrder(p, current);
        if (list.size() < 2) return;
        int at = 0;
        for (int i = 0; i < list.size(); i++) if (feedId(list.get(i)).equals(s.camera)) at = i;
        F next = list.get(Math.floorMod(at + (pressedRight ? 1 : -1), list.size()));
        s.switched = now();
        watch(p, next);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        long probeAt = probeStart();
        try {
            WatchSession s = sessions.get(e.getPlayer().getUniqueId());
            if (s == null || e.getTo() == null) return;
            if (s.lostUntil >= 0 && s.lostAt != null) {                  // held where the picture went
                Location to = e.getTo();
                if (to.getWorld() != s.lostAt.getWorld() || to.distanceSquared(s.lostAt) > 1e-4) {
                    Location hold = s.lostAt.clone();
                    hold.setYaw(to.getYaw());
                    hold.setPitch(to.getPitch());
                    e.setTo(hold);
                }
                return;
            }
            F f = feed(s.camera);
            Location eye = f == null ? null : eye(f);
            if (eye == null) return;
            Location to = e.getTo();
            if (to.getWorld() != eye.getWorld() || Math.abs(to.getX() - e.getFrom().getX()) + Math.abs(to.getY() - e.getFrom().getY())
                + Math.abs(to.getZ() - e.getFrom().getZ()) > 1e-4) {
                Vector d = clampToCone(dir(to.getYaw(), to.getPitch()), normal(f), limit(f));
                e.setTo(stand(e.getPlayer(), eye, yawOf(d), pitchOf(d)));
            }
        } finally {
            probeEnd("event.move", probeAt);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneakHeld(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) sneakers.add(e.getPlayer().getUniqueId());
        else sneakers.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            leave(e.getPlayer(), null, true);
        }
    }

    /** Spectators (watch-mode: spectator) can't fly off into other players' eyes or teleport about while watching. */
    @EventHandler(ignoreCancelled = true)
    public void onSpectate(PlayerStartSpectatingEntityEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** This engine's own teleports of a watcher (to the camera, back into the body). */
    public void move(Player p, Location to) {
        moving = true;
        try {
            p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            moving = false;
        }
    }

    /**
     * Spectator teleports are refused; anything else moving a watcher (/tp, a warp) stops the watching there. Being
     * put back at the camera (by onMove, which the server does as a teleport) is left alone.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (moving) return;
        WatchSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.ending) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            e.setCancelled(true);
            return;
        }
        if (s.lostUntil >= 0 && s.lostAt != null && e.getTo().getWorld() == s.lostAt.getWorld()
            && e.getTo().distanceSquared(s.lostAt) < 0.01) return;      // held while the signal's lost
        F f = feed(s.camera);
        Location eye = f == null ? null : eye(f);
        if (eye != null && e.getTo().getWorld() == eye.getWorld()
            && e.getTo().distanceSquared(stand(e.getPlayer(), eye, 0, 0)) < 0.01) return;
        s.ending = true;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> leave(p, null, false));
    }

    /** Hitting someone's body while they watch kicks them back into it, and they take the hit. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyHurt(EntityDamageEvent e) {
        Entity m = e.getEntity();
        if (m instanceof Player) return;
        String who = m.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (who == null) return;
        e.setCancelled(true);
        Player p = Bukkit.getPlayer(UUID.fromString(who));
        if (p == null) {
            m.remove();
            return;
        }
        double amount = e.getDamage();
        Entity by = e instanceof EntityDamageByEntityEvent ed ? ed.getDamager() : null;
        if (by instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity shooter) by = shooter;
        leave(p, msg("interrupted", "&cSomeone shook you away from the screen!"), true);
        if (settings().getBoolean("forward-damage", true) && amount > 0) {
            Entity attacker = by;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                if (attacker != null) p.damage(amount, attacker);
                else p.damage(amount);
            });
        }
    }

    /**
     * Someone watching who logs off or drops goes back into their body first. LOWEST, so it's before anything else
     * that saves where they are as they leave: SneakyCharacterManager saves the character's location in its own quit
     * listener and puts them there when they come back, which was at the camera.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        sneakers.remove(e.getPlayer().getUniqueId());
        if (sessions.containsKey(e.getPlayer().getUniqueId())) leave(e.getPlayer(), null, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!sessions.containsKey(p.getUniqueId())) shrink(p, false);   // left on by a crash mid-watch
        Bukkit.getScheduler().runTask(plugin, () -> restoreSaved(p));
    }

    // ------------------------------------------------------------------ the sessions file (a crash mid-watch)

    public void saveSessions() {
        YamlConfiguration y = new YamlConfiguration();
        for (WatchSession s : sessions.values()) {
            String k = "watching." + s.player + ".";
            y.set(k + "world", s.back.getWorld().getName());
            y.set(k + "x", s.back.getX());
            y.set(k + "y", s.back.getY());
            y.set(k + "z", s.back.getZ());
            y.set(k + "yaw", (double) s.back.getYaw());
            y.set(k + "pitch", (double) s.back.getPitch());
            y.set(k + "mode", s.mode.name());
            y.set(k + "allow-flight", s.allowFlight);
            y.set(k + "flying", s.flying);
            y.set(k + "fly-speed", (double) s.flySpeed);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(new File(plugin.getDataFolder(), sessionsFile));
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save " + sessionsFile + ": " + ex.getMessage());
        }
    }

    /** Someone still marked as watching (the server stopped without letting them go): put them back. */
    public void restoreSaved(Player p) {
        if (sessions.containsKey(p.getUniqueId())) return;
        File f = new File(plugin.getDataFolder(), sessionsFile);
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String k = "watching." + p.getUniqueId();
        if (!y.isConfigurationSection(k)) return;
        World w = Bukkit.getWorld(y.getString(k + ".world", ""));
        GameMode mode;
        try {
            mode = GameMode.valueOf(y.getString(k + ".mode", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            mode = GameMode.SURVIVAL;
        }
        p.setGameMode(mode);
        shrink(p, false);
        p.setInvisible(false);
        p.setCollidable(true);
        p.setFlySpeed((float) Math.max(-1, Math.min(1, y.getDouble(k + ".fly-speed", 0.1))));
        p.setAllowFlight(y.getBoolean(k + ".allow-flight"));
        if (w != null) p.teleport(new Location(w, y.getDouble(k + ".x"), y.getDouble(k + ".y"), y.getDouble(k + ".z"),
            (float) y.getDouble(k + ".yaw"), (float) y.getDouble(k + ".pitch")));
        if (p.getAllowFlight()) p.setFlying(y.getBoolean(k + ".flying"));
        y.set(k, null);
        try {
            y.save(f);
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save " + sessionsFile + ": " + ex.getMessage());
        }
        plugin.getLogger().info("Put " + p.getName() + " back where they were before watching a camera.");
    }
}
