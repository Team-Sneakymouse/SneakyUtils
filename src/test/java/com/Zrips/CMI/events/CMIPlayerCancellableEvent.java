package com.Zrips.CMI.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/** Test-only shape. */
public class CMIPlayerCancellableEvent extends CMIPlayerEvent implements Cancellable {
    private boolean cancel;

    public CMIPlayerCancellableEvent(Player player) {
        super(player);
    }

    @Override
    public final void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    @Override
    public final boolean isCancelled() {
        return cancel;
    }
}
