package net.sneakycharactermanager.paper.handlers.character

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/** Test-only API shape; no SCM runtime dependency is present. */
class LoadCharacterEvent(player: Player) : PlayerEvent(player), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(value: Boolean) { cancelled = value }
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
