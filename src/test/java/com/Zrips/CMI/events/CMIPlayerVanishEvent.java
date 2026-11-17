package com.Zrips.CMI.events;

import org.bukkit.entity.Player;

/** Test-only shape (no handler list of its own, like the real class). */
public class CMIPlayerVanishEvent extends CMIPlayerCancellableEvent {
    public CMIPlayerVanishEvent(Player player) {
        super(player);
    }
}
