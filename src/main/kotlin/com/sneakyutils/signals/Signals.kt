package com.sneakyutils.signals

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/*
 * Gameplay signals: plain synchronous Bukkit events defined once here, so any plugin can fire and hear them without
 * depending on another. Listen with @EventHandler as usual. Fire them on the server thread through the static
 * `fire(...)` helpers, which skip building the event when no plugin listens (HandlerList has no listeners).
 *
 * Maps passed in are copied; the event's maps are unmodifiable.
 */

/** A suspect was arrested, by a paladin player or by something else (null). */
class ArrestEvent(val paladin: Player?, val suspect: Player, val reason: String) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        @JvmStatic
        fun fire(paladin: Player?, suspect: Player, reason: String) {
            if (HANDLERS.registeredListeners.isEmpty()) return
            Bukkit.getPluginManager().callEvent(ArrestEvent(paladin, suspect, reason))
        }
    }
}

/** A player went on or off duty in a role (such as `paladin`). */
class DutyChangedEvent(val player: Player, val onDuty: Boolean, val role: String) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        @JvmStatic
        fun fire(player: Player, onDuty: Boolean, role: String) {
            if (HANDLERS.registeredListeners.isEmpty()) return
            Bukkit.getPluginManager().callEvent(DutyChangedEvent(player, onDuty, role))
        }
    }
}

/**
 * A player did something countable (`catch`, `cook`, `craft`, `mine`, ...), [amount] times. Skill XP is not a signal
 * here: SneakyLeveling owns that (`SkillXpGainEvent`).
 */
class ActivityEvent @JvmOverloads constructor(
    val player: Player,
    val activity: String,
    val amount: Int,
    context: Map<String, String>,
    location: Location? = null,
    /** The entity it happened to or with (a harvested mob, an NPC served), or null. */
    val target: Entity? = null,
) : Event() {
    /** Extra facts (item id, fish type, ...). */
    val context: Map<String, String> = java.util.Map.copyOf(context)

    /** Where it happened, when that matters (a fishing district, a quest region); a copy, or null. */
    val location: Location? = location?.clone()

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        /** Fires the event; builds nothing (no map copy, no location clone) when nobody listens. */
        @JvmStatic
        @JvmOverloads
        fun fire(
            player: Player,
            activity: String,
            amount: Int,
            context: Map<String, String> = emptyMap(),
            location: Location? = null,
            target: Entity? = null,
        ) {
            if (HANDLERS.registeredListeners.isEmpty()) return
            Bukkit.getPluginManager().callEvent(ActivityEvent(player, activity, amount, context, location, target))
        }
    }
}
