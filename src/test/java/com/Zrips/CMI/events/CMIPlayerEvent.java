package com.Zrips.CMI.events;

import org.bukkit.entity.Player;

/** Test-only shape: not a Bukkit PlayerEvent. */
public class CMIPlayerEvent extends CMIEvent {
    private final Player player;

    public CMIPlayerEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
