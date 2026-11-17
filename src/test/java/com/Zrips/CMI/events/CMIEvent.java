package com.Zrips.CMI.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Test-only shape of CMI 9.8.9.8's base event: one handler list shared by every CMI event. */
public class CMIEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
