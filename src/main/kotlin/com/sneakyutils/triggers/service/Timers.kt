package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.PlayerScope
import com.sneakyutils.triggers.PlayerTick
import com.sneakyutils.triggers.PlayerTimer
import com.sneakyutils.triggers.engine.BucketBalancer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Hands out phases for timers of the same interval so they don't all fire on the same tick: one [BucketBalancer] per
 * interval, least-loaded phase first. Separate instances serve global timers and per-player timer offsets.
 */
internal class PhaseAllocator {
    private val byInterval = HashMap<Int, BucketBalancer>()

    /** Reserves a phase (0 until [interval]); returns the bucket to give back with [release]. */
    fun acquire(interval: Int): Int = byInterval.getOrPut(interval) { BucketBalancer(interval) }.add()

    fun phaseOf(interval: Int, bucket: Int): Int = byInterval[interval]!!.phaseOf(bucket)

    fun release(interval: Int, bucket: Int) {
        val b = byInterval[interval] ?: return
        b.remove(bucket)
        if (b.total == 0) byInterval.remove(interval)
    }
}

/**
 * A global timer: runs [task] when the service's tick counter reaches [nextDue], then schedules the next run one
 * interval later. The counter advances by one per server tick, so a lagging server never runs it twice in a tick.
 */
internal class TimerSub(
    service: TriggerService,
    owner: Plugin,
    @JvmField val interval: Int,
    @JvmField val task: Runnable,
    @JvmField val phaseBucket: Int,
    @JvmField var nextDue: Long,
    label: String? = null,
) : SubBase(service, owner, KIND, label) {

    override fun release(silent: Boolean) = service.removeTimer(this)

    companion object {
        const val KIND = "timer"
    }
}

/**
 * A per-player timer. Each player sits in one bucket of a [BucketBalancer]; the bucket whose phase matches the
 * current tick runs, so N players on interval I cost about N / I calls a tick. Buckets are arrays replaced on change
 * (copy-on-write): a tick iterates a snapshot, and an entry removed meanwhile is skipped by its [Entry.active] flag.
 */
internal class PlayerTimerSub(
    service: TriggerService,
    owner: Plugin,
    @JvmField val interval: Int,
    @JvmField val handler: PlayerTick,
    @JvmField val scope: PlayerScope,
    @JvmField val offsetBucket: Int,
    /** Tick offset: bucket b fires when `(tick - offset) mod interval == phaseOf(b)`. */
    @JvmField val offset: Int,
    /** The first tick it may run: the tick after it was registered (also when registered during a tick). */
    @JvmField val firstTick: Long,
    /** Pause players from death to respawn (see [com.sneakyutils.triggers.PlayerTimerOptions.skipDead]). */
    @JvmField val skipDead: Boolean = true,
    label: String? = null,
) : SubBase(service, owner, if (scope == PlayerScope.ALL_ONLINE) KIND_ALL else KIND, label), PlayerTimer {

    /** One player in the timer: in [bucket], or paused ([PAUSED]: dead, in no bucket). */
    internal class Entry(@JvmField val player: Player, @JvmField val bucket: Int) {
        @JvmField var active = true
    }

    private val balancer = BucketBalancer(interval)
    private val buckets = arrayOfNulls<Array<Entry>>(balancer.buckets)
    private val entries = HashMap<UUID, Entry>()

    override fun add(p: Player) {
        service.requireMainThread()
        addInternal(p)
    }

    internal fun addInternal(p: Player) {
        if (closed || !p.isOnline || entries.containsKey(p.uniqueId)) return
        if (skipDead && p.isDead) {
            entries[p.uniqueId] = Entry(p, PAUSED).also { it.active = false }
            return
        }
        place(p)
    }

    /** Puts [p] in the least-loaded bucket. */
    private fun place(p: Player) {
        val b = balancer.add()
        val e = Entry(p, b)
        val old = buckets[b]
        buckets[b] = if (old == null) arrayOf(e) else old + e
        entries[p.uniqueId] = e
    }

    /** Takes [e] out of its bucket (a tick iterating a snapshot skips it by [Entry.active]). */
    private fun unplace(e: Entry) {
        e.active = false
        if (e.bucket == PAUSED) return
        balancer.remove(e.bucket)
        val old = buckets[e.bucket] ?: return
        buckets[e.bucket] = if (old.size == 1) null else old.filter { it !== e }.toTypedArray()
    }

    /** The player died: keep them in the timer, out of every bucket. */
    internal fun pause(id: UUID) {
        if (closed) return
        val e = entries[id] ?: return
        if (e.bucket == PAUSED) return
        unplace(e)
        entries[id] = Entry(e.player, PAUSED).also { it.active = false }
    }

    /** The player respawned: back into the least-loaded bucket. */
    internal fun resume(id: UUID) {
        if (closed) return
        val e = entries[id] ?: return
        if (e.bucket != PAUSED) return
        place(e.player)
    }

    override fun remove(p: Player) {
        service.requireMainThread()
        removeInternal(p.uniqueId)
    }

    internal fun removeInternal(id: UUID) {
        val e = entries.remove(id) ?: return
        unplace(e)
    }

    override fun contains(p: Player): Boolean {
        service.requireMainThread()
        return entries.containsKey(p.uniqueId)
    }

    override val size: Int
        get() {
            service.requireMainThread()
            return entries.size
        }

    /** [size] without the thread check, for the diagnostics sampler. */
    internal val sizeInternal: Int get() = entries.size

    /** Players per bucket, for tests of the spread. */
    internal fun bucketLoad(bucket: Int): Int = balancer.load(bucket)
    internal val bucketCount: Int get() = balancer.buckets

    /** Runs the bucket due at [tick], if any. No allocation. */
    fun fire(tick: Long) {
        val phase = Math.floorMod(tick - offset, interval.toLong()).toInt()
        val b = balancer.bucketAt(phase)
        if (b < 0) return
        val arr = buckets[b] ?: return
        for (e in arr) {
            if (closed) return
            if (!e.active) continue
            val p = e.player
            service.guard(this) { handler.tick(p) }
        }
    }

    override fun release(silent: Boolean) {
        for (e in entries.values) e.active = false
        entries.clear()
        buckets.fill(null)
        service.removePlayerTimer(this)
    }

    companion object {
        /** [Entry.bucket] of a paused (dead) player. */
        const val PAUSED = -1

        const val KIND = "player-timer"
        const val KIND_ALL = "player-timer-all-online"
    }
}
