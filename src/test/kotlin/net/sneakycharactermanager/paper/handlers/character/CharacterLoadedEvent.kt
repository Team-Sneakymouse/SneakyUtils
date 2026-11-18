package net.sneakycharactermanager.paper.handlers.character

import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/** Test-only API shape; no SCM runtime dependency is present. */
class CharacterLoadedEvent(player: Player) : PlayerEvent(player) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
