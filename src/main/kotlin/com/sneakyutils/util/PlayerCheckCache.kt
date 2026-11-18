package com.sneakyutils.util

import com.sneakyutils.signals.DutyChangedEvent
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.UUID

/**
 * Short-lived per-player cache for [PlayerUtil]'s PlaceholderAPI checks (CMI vanish, dispatch duty, SCM character id
 * and name). Owned by the enabled SneakyUtils plugin; server thread only ([PlayerUtil] skips it elsewhere).
 *
 * An entry lives for its check's configured ticks (`player-util.*-cache-ticks`, 0 = never cached), and is dropped on
 * quit, when a plugin it reads from enables or disables, and on the events that change it. Those events come from
 * plugins SneakyUtils doesn't compile against (CMI, SCM), so they are registered by class name through that plugin's
 * class loader when it enables. Most fire before the change happens, so for the rest of that tick the affected checks
 * bypass the cache (no lookup, no store), and the entries are dropped again on the next tick.
 */
internal class PlayerCheckCache(private val plugin: Plugin) : Listener {

    internal enum class Check { VANISH, DUTY, CHARACTER_ID, CHARACTER_NAME }

    private class Entry(@JvmField var value: Any?, @JvmField var tick: Int)

    private val ticks = IntArray(Check.entries.size)
    private val entries = Array(Check.entries.size) { HashMap<UUID, Entry>() }

    // Changes in progress: the tick of the last change event per check, for everyone or per player. While the current
    // tick equals it, that check is neither read from nor written to the cache. Ticks are only compared for equality
    // (safe across the int tick counter wrapping); "no mark" is a separate flag, not a reserved tick value.
    // pendingAny keeps hits cheap.
    private val pendingAnySet = BooleanArray(Check.entries.size)
    private val pendingAny = IntArray(Check.entries.size)
    private val pendingAllSet = BooleanArray(Check.entries.size)
    private val pendingAll = IntArray(Check.entries.size)
    private val pendingPlayers = Array(Check.entries.size) { HashMap<UUID, Int>() }

    /** One listener object per hooked plugin, so its registrations can be removed when that plugin disables. */
    private val hooks = HashMap<String, Listener>()

    fun configure(config: ConfigurationSection) {
        setTicks(Check.VANISH, readTicks(config, "player-util.vanish-cache-ticks"))
        setTicks(Check.DUTY, readTicks(config, "player-util.duty-cache-ticks"))
        val character = readTicks(config, "player-util.character-cache-ticks")
        setTicks(Check.CHARACTER_ID, character)
        setTicks(Check.CHARACTER_NAME, character)
    }

    private fun readTicks(config: ConfigurationSection, path: String): Int {
        val value = config.getInt(path, DEFAULT_TICKS)
        if (value >= 0) return value
        plugin.logger.warning("$path is $value; negative values turn the cache off (0).")
        return 0
    }

    /** Test seam: changes one check's lifetime and drops its entries. */
    fun setTicks(check: Check, value: Int) {
        ticks[check.ordinal] = value.coerceAtLeast(0)
        entries[check.ordinal].clear()
    }

    fun enabled(check: Check): Boolean = ticks[check.ordinal] > 0

    /** The cached value, or [MISS] when there is none or it has expired. Server thread. */
    fun lookup(check: Check, player: Player): Any? {
        val entry = entries[check.ordinal][player.uniqueId] ?: return MISS
        val now = Bukkit.getCurrentTick()
        if (changing(check, player.uniqueId, now)) return MISS
        val age = now - entry.tick
        return if (age in 0 until ticks[check.ordinal]) entry.value else MISS
    }

    /** Whether a change event for check (and this player, or everyone) fired this tick. */
    private fun changing(check: Check, player: UUID, now: Int): Boolean {
        val c = check.ordinal
        if (!pendingAnySet[c] || pendingAny[c] != now) return false
        return (pendingAllSet[c] && pendingAll[c] == now) || pendingPlayers[c][player] == now
    }

    /** Stores [value] as of this tick. Offline players aren't stored, so a stale Player can't leave an entry behind. */
    fun store(check: Check, player: Player, value: Any?) {
        if (!player.isOnline) return
        val now = Bukkit.getCurrentTick()
        if (changing(check, player.uniqueId, now)) return
        val map = entries[check.ordinal]
        val entry = map[player.uniqueId]
        if (entry == null) {
            map[player.uniqueId] = Entry(value, now)
        } else {
            entry.value = value
            entry.tick = now
        }
    }

    /** Drops [checks] for one player, or for everyone when [player] is null. Server thread. */
    fun invalidate(player: UUID?, checks: Set<Check>) {
        for (check in checks) {
            if (player == null) entries[check.ordinal].clear() else entries[check.ordinal].remove(player)
        }
    }

    /**
     * For an event fired before a change (or a provider being torn down): on the server thread, drops the entries now
     * and bypasses the cache for them for the rest of this tick; on any thread, drops them again on the next tick.
     * [player] null means every player.
     */
    fun invalidateNowAndNextTick(player: UUID?, checks: Set<Check>) {
        if (Bukkit.isPrimaryThread()) {
            invalidate(player, checks)
            val now = Bukkit.getCurrentTick()
            for (check in checks) {
                val c = check.ordinal
                pendingAnySet[c] = true
                pendingAny[c] = now
                if (player == null) {
                    pendingAllSet[c] = true
                    pendingAll[c] = now
                } else {
                    pendingPlayers[c][player] = now
                }
            }
        }
        if (!plugin.isEnabled) return
        try {
            plugin.server.scheduler.runTask(plugin, Runnable {
                invalidate(player, checks)
                clearOldMarks(Bukkit.getCurrentTick())
            })
        } catch (_: IllegalStateException) {
            // Disabling: the cache is dropped with the plugin.
        } catch (_: org.bukkit.plugin.IllegalPluginAccessException) {
        }
    }

    /** Forgets change marks from earlier ticks (equality only, so a wrapped tick counter can't keep them). */
    internal fun clearOldMarks(now: Int) {
        for (c in 0 until Check.entries.size) {
            if (pendingAnySet[c] && pendingAny[c] != now) pendingAnySet[c] = false
            if (pendingAllSet[c] && pendingAll[c] != now) pendingAllSet[c] = false
            pendingPlayers[c].values.removeIf { it != now }
        }
    }

    /** Test seam: whether any change mark is left. */
    internal fun hasMarks(): Boolean =
        pendingAnySet.any { it } || pendingAllSet.any { it } || pendingPlayers.any { it.isNotEmpty() }

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        for (name in HOOKED_PLUGINS.keys) {
            val target = plugin.server.pluginManager.getPlugin(name)
            if (target != null && target.isEnabled) hook(target)
        }
    }

    fun shutdown() {
        for (listener in hooks.values) HandlerList.unregisterAll(listener)
        hooks.clear()
        HandlerList.unregisterAll(this)
        for (map in entries) map.clear()
        for (map in pendingPlayers) map.clear()
        pendingAnySet.fill(false)
        pendingAllSet.fill(false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // Again next tick: later quit listeners may still ask about the player while they count as online.
        invalidateNowAndNextTick(event.player.uniqueId, ALL)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDutyChanged(event: DutyChangedEvent) {
        invalidateNowAndNextTick(event.player.uniqueId, DUTY)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) {
        PLUGIN_CHECKS[event.plugin.name]?.let { invalidate(null, it) }
        if (event.plugin.name in HOOKED_PLUGINS) hook(event.plugin)
    }

    /**
     * Paper fires this before the provider's own shutdown runs, so its answers can still change this tick: bypass the
     * checks that read from it for the rest of the tick (from the first listener on) and drop them again next tick.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPluginDisable(event: PluginDisableEvent) {
        PLUGIN_CHECKS[event.plugin.name]?.let { invalidateNowAndNextTick(null, it) }
        hooks.remove(event.plugin.name)?.let { HandlerList.unregisterAll(it) }
    }

    /** Registers [target]'s invalidation events by class name (replacing an earlier hook of the same plugin). */
    private fun hook(target: Plugin) {
        hooks.remove(target.name)?.let { HandlerList.unregisterAll(it) }
        val listener = object : Listener {}
        hooks[target.name] = listener
        for (signal in HOOKED_PLUGINS[target.name].orEmpty()) {
            val registered =
                registerByName(target, signal.className, listener, signal.checks, signal.priority, signal.everyone)
            if (!registered && !signal.optional) {
                plugin.logger.warning(
                    "${target.name} has no usable ${signal.className}; cached player checks expire by time only.",
                )
            }
        }
    }

    /**
     * Loads [className] through [target]'s class loader and listens to it at [priority] (cancelled events too: dropping
     * an entry is harmless). [everyone] drops the checks for every player instead of the event's player. Returns false
     * when the class is missing or isn't a registrable event.
     */
    fun registerByName(
        target: Plugin,
        className: String,
        listener: Listener,
        checks: Set<Check>,
        priority: EventPriority = EventPriority.MONITOR,
        everyone: Boolean = false,
    ): Boolean {
        val eventClass = try {
            Class.forName(className, false, target.javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            return false
        } catch (_: LinkageError) {
            return false
        } catch (_: RuntimeException) {
            // Some plugin class loaders fail a missing class with an unchecked exception instead.
            return false
        }
        if (!Event::class.java.isAssignableFrom(eventClass)) return false
        val type = eventClass.asSubclass(Event::class.java)
        return try {
            // The handler list can belong to a superclass shared with other events (CMI's CMIEvent), so filter.
            plugin.server.pluginManager.registerEvent(type, listener, priority, { _, event ->
                if (type.isInstance(event)) {
                    val player = if (everyone) null else (event as? PlayerEvent)?.player?.uniqueId
                    invalidateNowAndNextTick(player, checks)
                }
            }, plugin, false)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private class Signal(
        val className: String,
        val checks: Set<Check>,
        val optional: Boolean = false,
        val priority: EventPriority = EventPriority.MONITOR,
        val everyone: Boolean = false,
    )

    companion object {
        const val DEFAULT_TICKS = 20

        /** Returned by [lookup] when there is no live entry (null is a cached value: "no character"). */
        @JvmField
        val MISS = Any()

        /** The enabled plugin's cache; null while SneakyUtils isn't enabled. Volatile: read from any thread. */
        @Volatile
        @JvmStatic
        var active: PlayerCheckCache? = null

        val ALL: Set<Check> = EnumSet.allOf(Check::class.java)
        private val VANISH: Set<Check> = EnumSet.of(Check.VANISH)
        private val DUTY: Set<Check> = EnumSet.of(Check.DUTY)
        /** A character load or swap: also duty, which SneakyDispatch derives from the worn character's paladin tag. */
        private val CHARACTER_AND_DUTY: Set<Check> = EnumSet.of(Check.CHARACTER_ID, Check.CHARACTER_NAME, Check.DUTY)
        private val NAME: Set<Check> = EnumSet.of(Check.CHARACTER_NAME)

        const val CMI = "CMI"
        const val SCM = "SneakyCharacterManager"
        private val LOWEST = EventPriority.LOWEST
        private const val SCM_EVENTS = "net.sneakycharactermanager.paper.handlers.character"

        /**
         * Events that change a check, per plugin. CMI 9.8.9.8: both events extend CMIPlayerCancellableEvent (handler
         * list on CMIEvent, shared by every CMI event) and fire before the vanish state changes. SCM: LoadCharacterEvent
         * and CharacterNickChangeEvent fire before the change and can be cancelled; CharacterLoadedEvent (newer SCM)
         * fires after a load and for each body of a swap (which fires no LoadCharacterEvent). Loads and swaps also
         * drop duty. A swap changes two bodies but its events arrive one at a time, so both load events are heard at
         * LOWEST and drop id, name and duty for every player. Listeners at LOWEST that were registered before SneakyUtils'
         * hook still run first and can see a cached value. Loads are rare, so the extra reads are cheap.
         */
        private val HOOKED_PLUGINS: Map<String, List<Signal>> = mapOf(
            CMI to listOf(
                Signal("com.Zrips.CMI.events.CMIPlayerVanishEvent", VANISH),
                Signal("com.Zrips.CMI.events.CMIPlayerUnVanishEvent", VANISH),
            ),
            SCM to listOf(
                Signal("$SCM_EVENTS.LoadCharacterEvent", CHARACTER_AND_DUTY, priority = LOWEST, everyone = true),
                Signal(
                    "$SCM_EVENTS.CharacterLoadedEvent", CHARACTER_AND_DUTY,
                    optional = true, priority = LOWEST, everyone = true,
                ),
                Signal("$SCM_EVENTS.CharacterNickChangeEvent", NAME),
            ),
        )

        /** Which checks read from each plugin (its placeholders), dropped when it enables or disables. */
        private val PLUGIN_CHECKS: Map<String, Set<Check>> = mapOf(
            "PlaceholderAPI" to ALL,
            CMI to VANISH,
            SCM to CHARACTER_AND_DUTY,
            "SneakyDispatch" to DUTY,
        )
    }
}
