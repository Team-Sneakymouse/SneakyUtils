package com.sneakyutils.targets

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Virtual targets: things a weapon can hit that are not entities (a horde's figures, drawn with packets). Nothing
 * that looks for entities finds them, so a weapon that finds its own targets (a hitscan shot, a blast of its own)
 * also calls [VirtualTargets] once, and every plugin that owns such targets answers. Usage: docs/virtual-targets.md.
 *
 * Server thread only. Every signature here is Java-callable and published: add, don't change.
 */

/**
 * What a plugin that owns virtual targets does when a weapon strikes. Extend it, override what the targets can
 * take, and [VirtualTargets.register] it while they exist. What isn't overridden hits nothing, so methods added
 * here later don't break a provider built against this version.
 *
 * The locations and vectors passed in are the provider's own copies. [direction] is a unit vector.
 */
abstract class VirtualTargetProvider {
    /** Hurt every target within [radius] of the line. @return how many were hit */
    open fun damageLine(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Int = 0

    /**
     * Where the line first comes within [radius] of a target, hurting nothing; null when it meets none. This is
     * how a shot finds its first target among every plugin's: a provider that takes [damageFirst] must answer it.
     */
    open fun firstAlong(from: Location, direction: Vector, length: Double, radius: Double): Location? = null

    /**
     * Hurt only the first target on the line. Only called on the provider whose [firstAlong] was the nearest.
     * @return where it was hit, or null
     */
    open fun damageFirst(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Location? = null

    /** Hurt every target within [radius] of [center], all by the same [damage]. @return how many were hit */
    open fun damageSphere(center: Location, radius: Double, damage: Double, source: Entity?): Int = 0

    /** Hurt the targets around [center] as a vanilla explosion of [power] would (4 = TNT). @return how many were hit */
    open fun damageExplosion(center: Location, power: Double, source: Entity?): Int = 0
}

/** The one call a weapon makes to hit every plugin's virtual targets. Costs next to nothing while none are registered. */
object VirtualTargets {
    private class Entry(val owner: Plugin, val provider: VirtualTargetProvider)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val failed = HashSet<String>()

    /**
     * Adds [provider] for [owner]'s virtual targets. It is dropped by itself when [owner] is disabled; call
     * [unregister] to end it sooner. Registering the same provider twice does nothing, and neither does registering
     * for an owner that isn't enabled.
     */
    @JvmStatic
    fun register(owner: Plugin, provider: VirtualTargetProvider) {
        if (owner.isEnabled && entries.none { it.provider === provider }) entries += Entry(owner, provider)
    }

    /** @return whether [provider] was registered */
    @JvmStatic
    fun unregister(provider: VirtualTargetProvider): Boolean = entries.removeIf { it.provider === provider }

    /** Whether any plugin has virtual targets right now: a weapon can skip its own setup when not. */
    @JvmStatic
    fun any(): Boolean = live().isNotEmpty()

    /**
     * Hurts every virtual target within [radius] of the line from [from] along [direction] for [length] blocks (a
     * shot that goes through everything in its way; cut [length] at the first wall yourself).
     * @return how many targets were hit
     */
    @JvmStatic
    fun damageLine(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Int {
        val dir = unit(direction) ?: return 0
        if (!line(length, radius) || damage.isNaN()) return 0
        var n = 0
        for (e in live()) n += guard(e, 0) { it.damageLine(from.clone(), dir.clone(), length, radius, damage, source) }
        return n
    }

    /** Where that line first comes within [radius] of a virtual target (for aiming); null when it meets none. */
    @JvmStatic
    fun firstAlong(from: Location, direction: Vector, length: Double, radius: Double): Location? {
        val dir = unit(direction) ?: return null
        if (!line(length, radius)) return null
        return nearest(from, dir, length, radius)?.second
    }

    /**
     * Hurts only the first virtual target on that line (a shot that stops in what it hits).
     * @return where it was hit, or null when the line met none
     */
    @JvmStatic
    fun damageFirst(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Location? {
        val dir = unit(direction) ?: return null
        if (!line(length, radius) || damage.isNaN()) return null
        // Ask every owner where its first target is, then strike the nearest's: the same with one owner as with ten.
        val entry = nearest(from, dir, length, radius)?.first ?: return null
        return guard(entry, null) { it.damageFirst(from.clone(), dir.clone(), length, radius, damage, source) }
    }

    /**
     * Hurts every virtual target within [radius] of [center], all by the same [damage].
     * @return how many targets were hit
     */
    @JvmStatic
    fun damageSphere(center: Location, radius: Double, damage: Double, source: Entity?): Int {
        if (!radius.isFinite() || radius < 0 || damage.isNaN()) return 0
        var n = 0
        for (e in live()) n += guard(e, 0) { it.damageSphere(center.clone(), radius, damage, source) }
        return n
    }

    /**
     * Hurts the virtual targets around [center] as a vanilla explosion of [power] would (4 = TNT), less the further
     * away. Real explosions reach them by themselves; this is for a blast a plugin makes without one.
     * @return how many targets were hit
     */
    @JvmStatic
    fun damageExplosion(center: Location, power: Double, source: Entity?): Int {
        if (!power.isFinite() || power <= 0) return 0
        var n = 0
        for (e in live()) n += guard(e, 0) { it.damageExplosion(center.clone(), power, source) }
        return n
    }

    private fun nearest(from: Location, dir: Vector, length: Double, radius: Double): Pair<Entry, Location>? {
        var best: Pair<Entry, Location>? = null
        var bestD = Double.MAX_VALUE
        for (e in live()) {
            val at = guard(e, null) { it.firstAlong(from.clone(), dir.clone(), length, radius) } ?: continue
            if (at.world != from.world) continue
            val d = at.distanceSquared(from)
            if (d < bestD) { bestD = d; best = e to at }
        }
        return best
    }

    /** The providers whose owners are enabled; the rest are dropped. */
    private fun live(): List<Entry> {
        if (entries.isEmpty()) return emptyList()
        entries.removeIf { !it.owner.isEnabled }
        return entries
    }

    /** A line a provider can work with: a finite length to go and a finite, non-negative width. Else nothing is hit. */
    private fun line(length: Double, radius: Double): Boolean = length.isFinite() && length > 0 && radius.isFinite() && radius >= 0

    /** [owner] was disabled: its providers go at once, not at the next shot. */
    internal fun dropOwner(owner: Plugin) {
        entries.removeIf { it.owner === owner }
    }

    /** SneakyUtils is shutting down. */
    internal fun clear() {
        entries.clear()
        failed.clear()
    }

    private fun unit(direction: Vector): Vector? {
        val len = direction.length()
        return if (len < 1e-9 || !len.isFinite()) null else direction.clone().multiply(1 / len)
    }

    /** One provider failing must not cost the weapon its shot, nor the other providers theirs. */
    private inline fun <T> guard(entry: Entry, fallback: T, call: (VirtualTargetProvider) -> T): T =
        try {
            // Checked again here: an earlier provider's hit (its damage event) may have disabled or unregistered this one.
            if (entry.owner.isEnabled && entries.contains(entry)) call(entry.provider) else fallback
        } catch (t: Throwable) {
            if (t !is Exception && t !is LinkageError) throw t
            if (failed.add(entry.provider.javaClass.name)) {
                Bukkit.getLogger().warning("[SneakyUtils] ${entry.owner.name}'s virtual targets failed (logged once): $t")
            }
            fallback
        }
}

/** Drops a plugin's providers when it is disabled. Registered by SneakyUtils. */
class VirtualTargetsListener : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDisable(event: PluginDisableEvent) = VirtualTargets.dropOwner(event.plugin)
}

/**
 * A virtual target is about to be hurt, whatever by. Providers fire it for every hit; a weapon listens to apply its
 * own rule (what its arrows do to monsters) by changing [damage], or cancels it and the target is not hit at all.
 *
 * @property owner the plugin whose target it is
 * @property location the target's feet
 * @property kind what it is, as its owner names it
 * @property health its health before this hit
 * @property source who did it (a projectile's shooter, a weapon's holder), when known
 * @property projectile the projectile that hit it, when it was one
 */
class VirtualTargetDamageEvent(
    val owner: Plugin,
    val location: Location,
    val kind: String,
    var damage: Double,
    val health: Double,
    val source: Entity?,
    val projectile: Projectile?,
) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        /**
         * For providers: asks the listeners about a hit, without building the event when nobody listens.
         * @return the damage to deal, or NaN when a listener cancelled the hit
         */
        @JvmStatic
        fun fire(owner: Plugin, location: Location, kind: String, damage: Double, health: Double, source: Entity?, projectile: Projectile?): Double {
            if (HANDLERS.registeredListeners.isEmpty()) return damage
            val event = VirtualTargetDamageEvent(owner, location, kind, damage, health, source, projectile)
            Bukkit.getPluginManager().callEvent(event)
            return if (event.isCancelled) Double.NaN else event.damage
        }
    }
}
