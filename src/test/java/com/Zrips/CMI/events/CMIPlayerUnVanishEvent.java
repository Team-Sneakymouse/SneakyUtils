package com.Zrips.CMI.events;

import org.bukkit.entity.Player;

/** Test-only shape (no handler list of its own, like the real class). */
public class CMIPlayerUnVanishEvent extends CMIPlayerCancellableEvent {
    public CMIPlayerUnVanishEvent(Player player) {
        super(player);
    }
}
