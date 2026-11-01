package com.sneakyutils.util.items;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/** Optional SCM hook resolved from SCM's own loader: no reverse load-order or classpath edge. */
public final class CharacterExpirationHook implements Listener {
    private final Plugin owner;
    private final ItemExpirationListener expiration;
    private Listener registration;
    public CharacterExpirationHook(Plugin owner, ItemExpirationListener expiration) {
        this.owner = owner;
        this.expiration = expiration;
        ensureRegistered(Bukkit.getPluginManager().getPlugin("SneakyCharacterManager"));
    }
    @EventHandler public void onEnable(PluginEnableEvent event) { ensureRegistered(event.getPlugin()); }
    @EventHandler public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("SneakyCharacterManager") && registration != null) {
            HandlerList.unregisterAll(registration);
            registration = null;
        }
    }
    @SuppressWarnings("unchecked")
    private void ensureRegistered(Plugin scm) {
        if (registration != null || scm == null || !scm.isEnabled()
                || !scm.getName().equals("SneakyCharacterManager")) return;
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(
                    "net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent", true,
                    scm.getClass().getClassLoader()).asSubclass(Event.class);
            Method getPlayer = type.getMethod("getPlayer");
            Listener listener = new Listener() {};
            Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.LOWEST, (ignored, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Player player = (Player) getPlayer.invoke(event);
                    Bukkit.getScheduler().runTask(owner, () -> {
                        if (owner.isEnabled() && scm.isEnabled() && player.isOnline()
                                && (!(event instanceof org.bukkit.event.Cancellable cancelled) || !cancelled.isCancelled()))
                            expiration.joinOrLoadCharacter(player);
                    });
                } catch (ReflectiveOperationException exception) {
                    throw new org.bukkit.event.EventException(exception);
                }
            }, owner, true);
            registration = listener;
        } catch (ReflectiveOperationException | LinkageError exception) {
            owner.getLogger().warning("SCM character expiration hook unavailable: " + exception.getClass().getSimpleName());
        }
    }
}
