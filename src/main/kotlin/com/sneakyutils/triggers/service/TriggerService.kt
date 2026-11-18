package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.ActionResolver
import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.AreaHandler
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockBreakHandler
import com.sneakyutils.triggers.BlockClickHandler
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.BlockRemovedHandler
import com.sneakyutils.triggers.BlockRemovedOptions
import com.sneakyutils.triggers.BlockWatchHandler
import com.sneakyutils.triggers.BreakOptions
import com.sneakyutils.triggers.ClickSide
import com.sneakyutils.triggers.BlockUnderHandler
import com.sneakyutils.triggers.ClickOptions
import com.sneakyutils.triggers.EntityClickHandler
import com.sneakyutils.triggers.EntityMatch
import com.sneakyutils.triggers.PlayerScope
import com.sneakyutils.triggers.PlayerTick
import com.sneakyutils.triggers.PlayerTimer
import com.sneakyutils.triggers.PlayerTimerOptions
import com.sneakyutils.triggers.StepHandler
import com.sneakyutils.triggers.Subscription
import com.sneakyutils.triggers.SubscriptionStats
import com.sneakyutils.triggers.TableLoadResult
import com.sneakyutils.triggers.TimerOptions
import com.sneakyutils.triggers.TriggerStats
import com.sneakyutils.triggers.Triggers
import com.sneakyutils.triggers.WatchOptions
import com.sneakyutils.triggers.engine.AreaIndex
import com.sneakyutils.triggers.engine.AreaReg
import com.sneakyutils.triggers.engine.BucketBalancer
import com.sneakyutils.triggers.engine.EngineSink
import com.sneakyutils.triggers.engine.EventQueue
import com.sneakyutils.triggers.engine.Geometry
import com.sneakyutils.triggers.engine.MovementCore
import com.sneakyutils.triggers.engine.PlayerState
import com.sneakyutils.triggers.engine.StepWatch
import com.sneakyutils.triggers.engine.BlockWatch
import com.sneakyutils.triggers.engine.Watch
import com.sneakyutils.integration.Probe
import org.bukkit.GameMode
import org.bukkit.event.Listener
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import java.util.logging.Level

/**
 * The [Triggers] implementation: owns the movement engine, the subscription registry, the 1-tick ticker (mounted
 * poll and timers) and the listeners. Created by SneakyUtils on enable, shut down on disable.
 */
internal class TriggerService(
    @JvmField val plugin: Plugin,
    /** SneakyDiagnostics shim; null in tests. Its methods do nothing when SneakyDiagnostics is absent. */
    @JvmField val probe: Probe? = null,
    maxSegmentBlocks: Int = MovementCore.DEFAULT_MAX_SEGMENT_BLOCKS,
) : Triggers, EngineSink {

    @JvmField val index = AreaIndex()
    @JvmField val core = MovementCore(index, this, maxSegmentBlocks)
    @JvmField val clicks = ClickIndex(this)
    @JvmField val removals = RemovalIndex(this)
    private val tables = TableLoader(this)

    /** Active subscriptions by owner. Identity keys: plugins don't override equals. */
    private val byOwner = IdentityHashMap<Plugin, LinkedHashSet<SubBase>>()
    private var sequence = 0L
    private var ticker: BukkitTask? = null
    private var listener: TriggerListener? = null

    /** Registered on the first removal subscription and kept until shutdown ([ensureRemovalListener]). */
    private var removalListener: BlockRemovedListener? = null
    private var oldPrimeListener: Listener? = null
    private val pollLocation = Location(null, 0.0, 0.0, 0.0)

    @JvmField var running = false

    // Always-on counters for events the listener sees (the engine keeps its own).
    @JvmField var moveEvents = 0L
    @JvmField var teleportEvents = 0L
    @JvmField var mountedPolls = 0L
    @JvmField var handlerCalls = 0L
    @JvmField var handlerErrors = 0L
    @JvmField var ticks = 0L
    @JvmField var resyncs = 0L
    @JvmField var riderTeleports = 0L

    /** Nanoseconds spent in the mounted poll while timing is on (its call count is `polls.mounted`). */
    @JvmField var pollNanos = 0L

    /** Handler calls of [SLOW_HANDLER_NANOS] or more while timing was on. */
    @JvmField var slowCalls = 0L

    /** Measure handler time (a SneakyDiagnostics capture or `/triggers profile on`). See [refreshTiming]. */
    @JvmField var timing = false

    /** Whether a SneakyDiagnostics capture was running at the last [refreshTiming]. */
    @JvmField var recording = false

    /** Set by `/triggers profile on|off`: time handlers with System.nanoTime without a capture. */
    @JvmField var profileOn = false

    /** Section and count names for captures (handler attribution). */
    @JvmField val diag = TriggerDiagnostics(probe)

    /** Engine stages open on the stack ([stage]): bit per stage, so a stage never nests inside itself. */
    @JvmField var openStages = 0

    private var sampler: BukkitTask? = null
    private var sampling = false
    private var sampleBaseline: Map<String, Long> = emptyMap()

    /**
     * Timing is on while profiling or while a SneakyDiagnostics capture runs. Read once per drain, tick, matched click,
     * riding or resync pass and sample (one interface call), never per handler and never on the no-op path. A capture
     * that starts begins a new set of diagnostics names ([TriggerDiagnostics.newCapture]).
     */
    fun refreshTiming() {
        val pr = probe
        val on = pr != null && pr.on()
        if (on != recording) {
            recording = on
            if (on) diag.newCapture()
        }
        timing = profileOn || on
    }

    /**
     * Runs [block] as engine stage [name] (a SneakyDiagnostics section) while timing is on; otherwise just runs it
     * (one field read). A stage already open further up the stack isn't opened again (a teleport from a teleport
     * handler stays inside the outer `teleport`), so sections nest strictly and never inside their own name.
     */
    inline fun stage(bit: Int, name: String, block: () -> Unit) {
        val pr = probe
        if (!timing || pr == null || (openStages and bit) != 0) {
            block()
            return
        }
        val token = pr.start()
        openStages = openStages or bit
        try {
            block()
        } finally {
            openStages = openStages and bit.inv()
            if (token != 0L) pr.end(name, token)
        }
    }

    // Timers: arrays replaced on change (copy-on-write), so a tick iterates a snapshot.
    private var globalTimers = arrayOf<TimerSub>()
    private var playerTimers = arrayOf<PlayerTimerSub>()
    private val globalPhases = PhaseAllocator()
    private val playerPhases = PhaseAllocator()

    /** Whether the dense-chunk warning was logged (once per enable). */
    private var denseWarned = false

    /** Wall clock for the error-log rate limit; replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun nextSequence(): Long = ++sequence

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        check(!running) { "already started" }
        running = true
        val l = TriggerListener(this)
        listener = l
        plugin.server.pluginManager.registerEvents(l, plugin)
        // Players already online (plugin enabled late, or a reload).
        for (p in plugin.server.onlinePlayers) playerJoined(p)
        // Counters and levels for SneakyDiagnostics captures, once a second; only when it is installed (or its service
        // appears later: diagnosticsChanged).
        if (plugin.server.pluginManager.getPlugin("SneakyDiagnostics") != null) startSampler()
    }

    private fun startSampler() {
        if (probe == null || sampler != null || !running) return
        sampler = plugin.server.scheduler.runTaskTimer(plugin, Runnable { sample() }, 20L, 20L)
    }

    /**
     * SneakyDiagnostics' service was registered or unregistered (the listener calls this at MONITOR, after the shim
     * attached or dropped its probe). A service that appears after startup gets the sampler; either way timing is
     * re-read, so a stale "capture running" never outlives the service.
     */
    fun diagnosticsChanged(registered: Boolean) {
        if (registered) startSampler()
        refreshTiming()
    }

    /**
     * While a capture runs: reports the always-on counters as counts (the change since the last sample, so no
     * per-event probe call) and, when due, levels. The only sampleDue() site in SneakyUtils.
     */
    private fun sample() {
        val pr = probe ?: return
        refreshTiming()
        if (!recording) {
            sampling = false
            return
        }
        val now = counters()
        if (sampling) {
            for ((k, v) in now) {
                val d = v - (sampleBaseline[k] ?: 0L)
                if (d > 0) pr.count(k, d)
            }
        }
        sampleBaseline = now
        sampling = true
        if (pr.sampleDue()) {
            pr.level("players.tracked", core.playerCount.toLong())
            pr.level("players.riding", core.mountedCount.toLong())
            pr.level("areas", index.areaCount.toLong())
            pr.level("areas.chunks", index.chunkEntries.toLong())
            pr.level("timers", globalTimers.size.toLong())
            pr.level("timers.per-player", playerTimers.size.toLong())
            pr.level("timers.all-online", playerTimers.count { it.scope == PlayerScope.ALL_ONLINE }.toLong())
            pr.level("subscriptions", byOwner.values.sumOf { it.size }.toLong())
            pr.level("players.watched", core.watchedPlayerCount.toLong())
            pr.level("timers.per-player.players", playerTimers.sumOf { it.sizeInternal }.toLong())
            sampleSubscriptionLevels(pr)
        }
    }

    /**
     * Levels `subscriptions.by-kind.<kind>` and `subscriptions.by-owner.<owner>.<kind>`: the first
     * [TriggerDiagnostics.OWNER_LEVELS] owner/kind pairs of the capture by name, the rest summed in
     * `subscriptions.by-owner.other`. Every pair seen in the capture is reported each second, as 0 when it has none.
     */
    private fun sampleSubscriptionLevels(pr: Probe) {
        val kinds = java.util.TreeMap<String, Long>()
        val owners = diag.ownerLevels
        for (key in owners.keys) owners[key] = 0L
        var other = 0L
        for (set in byOwner.values) {
            for (sub in set) {
                kinds.merge(sub.kind, 1L, Long::plus)
                val key = "subscriptions.by-owner.${sub.ownerKey}.${sub.kind}"
                val n = owners[key]
                if (n != null) owners[key] = n + 1
                else if (owners.size < TriggerDiagnostics.OWNER_LEVELS) owners[key] = 1L
                else other++
            }
        }
        for (kind in KINDS) pr.level("subscriptions.by-kind.$kind", kinds[kind] ?: 0L)
        for ((key, n) in owners) pr.level(key, n)
        if (other > 0 || owners.size >= TriggerDiagnostics.OWNER_LEVELS) pr.level("subscriptions.by-owner.other", other)
    }

    /**
     * The 1-tick ticker runs only while it has work (a riding player or a timer) and stops itself when idle, so an
     * idle server pays nothing for it.
     */
    fun ensureTicker() {
        if (ticker != null || !running) return
        ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    private fun tickerHasWork(): Boolean =
        core.mountedCount > 0 || globalTimers.isNotEmpty() || playerTimers.isNotEmpty()

    /** Closes everything without firing exits (SneakyUtils disable). */
    fun shutdown() {
        if (!running) return
        running = false
        ticker?.cancel()
        ticker = null
        sampler?.cancel()
        sampler = null
        listener?.let { org.bukkit.event.HandlerList.unregisterAll(it) }
        listener = null
        removalListener?.let { org.bukkit.event.HandlerList.unregisterAll(it) }
        removalListener = null
        removals.shutdown()
        oldPrimeListener?.let { org.bukkit.event.HandlerList.unregisterAll(it) }
        oldPrimeListener = null
        pendingResync.clear()
        pendingRiding.clear()
        tables.clear()
        val all = byOwner.values.flatten()
        byOwner.clear()
        for (sub in all) sub.dispose()
        core.shutdown()
    }

    fun requireMainThread() {
        check(plugin.server.isPrimaryThread) { "Triggers must be used on the server thread" }
    }

    private fun checkCall(owner: Plugin) {
        requireMainThread()
        check(running) { "SneakyUtils triggers are not running" }
        require(owner.isEnabled) { "owner ${owner.name} is not enabled" }
    }

    private fun <S : SubBase> register(sub: S): S {
        byOwner.getOrPut(sub.owner) { LinkedHashSet() }.add(sub)
        return sub
    }

    fun unregister(sub: SubBase) {
        val set = byOwner[sub.owner] ?: return
        set.remove(sub)
        if (set.isEmpty()) byOwner.remove(sub.owner)
    }

    /** Silently closes every subscription of a disabling plugin. */
    fun closeOwner(owner: Plugin) {
        tables.ownerDisabled(owner)
        val set = byOwner.remove(owner)
        if (set != null) for (sub in set.toList()) sub.dispose()
        // Players standing still still cache their chunk's old area array (it is re-read on their next block step).
        // Drop those now, so they don't keep the disabled plugin's handlers, and with them its classes, reachable.
        // Also when the plugin had closed everything itself already: its closed areas may still be cached.
        core.refreshStaleCaches()
    }

    /** Every active subscription (a copy). */
    fun subscriptions(): List<SubBase> = byOwner.values.flatten()

    // ---------------------------------------------------------------- Bukkit inputs (from the listener and ticker)

    fun playerJoined(p: Player) = stage(STAGE_PLAYER, "player-state") {
        val loc = p.location
        val world = loc.world ?: return@stage
        val riding = p.isInsideVehicle
        // A player on the death screen (SneakyUtils enabling meanwhile, or a rejoin before respawning) stays out of
        // areas until the respawn; per-player timers that skip the dead pause them on add (PlayerTimerSub.addInternal).
        core.join(p.uniqueId, p, world.uid, loc.x, loc.y, loc.z, riding, p.gameMode == GameMode.SPECTATOR, p.isDead)
        if (riding) ensureTicker()
        for (t in playerTimers) if (t.scope == PlayerScope.ALL_ONLINE) t.addInternal(p)
    }

    /** A PlayerMoveEvent that changed the block position: the hot path, timed as stage `move` during a capture. */
    fun moved(id: UUID, worldId: UUID, x: Double, y: Double, z: Double) = stage(STAGE_MOVE, "move") {
        core.position(id, worldId, x, y, z, false)
    }

    // ---- teleport reconciliation
    // Bukkit applies a teleport's (or respawn's) destination after its event. A handler that teleports the same
    // player while we handle that event is overridden, so the engine would believe the inner destination. We note
    // such nesting and, on the next tick, feed the player's real position (one task for all players, only then).

    /** The player whose teleport or respawn event is being handled right now (null outside one). */
    private var overridingEventPlayer: UUID? = null
    private val pendingResync = LinkedHashSet<UUID>()
    private var resyncScheduled = false

    /** Players waiting for a resync, for leak tests. */
    internal val pendingResyncCount: Int get() = pendingResync.size

    /**
     * A PlayerTeleportEvent. For a riding player in the same world it counts as vehicle movement, with path checks:
     * Paper fires a teleport event for the rider every tick when the mount is moved by teleport (measured on Paper
     * 26.2), so fast teleport-driven vehicles must not skip pass-through areas. The engine still treats a jump longer
     * than the segment limit (64 blocks) as a teleport. The mounted poll reading the same block later is deduped.
     * A [dismount] (Paper's teleport from the seat to beside the vehicle) is a move on foot in the same world: cause
     * MOVE, with path checks, like any step.
     */
    fun teleported(p: Player, worldId: UUID, x: Double, y: Double, z: Double, dismount: Boolean = false) =
        stage(STAGE_TELEPORT, "teleport") { teleportedInternal(p, worldId, x, y, z, dismount) }

    private fun teleportedInternal(p: Player, worldId: UUID, x: Double, y: Double, z: Double, dismount: Boolean) {
        val id = p.uniqueId
        if (overridingEventPlayer == id) requestResync(id)
        if (core.state(id) == null) return
        val outer = overridingEventPlayer
        overridingEventPlayer = id
        try {
            // Re-read the riding state first (a field read): a mount made without an event is picked up here.
            val s = syncRiding(p) ?: return
            val sameWorld = worldId == s.worldId
            val riding = s.mounted && sameWorld
            if (riding) riderTeleports++
            core.position(s, worldId, x, y, z, !riding && !(dismount && sameWorld))
        } finally {
            overridingEventPlayer = outer
        }
    }

    /** The player changed world (they are in the new world now). Riding is re-read first, like on a teleport. */
    fun worldChanged(p: Player) = stage(STAGE_PLAYER, "player-state") {
        val loc = p.location
        val world = loc.world ?: return@stage
        syncRiding(p) ?: return@stage
        core.position(p.uniqueId, world.uid, loc.x, loc.y, loc.z, true)
    }

    fun respawned(p: Player, worldId: UUID, x: Double, y: Double, z: Double) =
        stage(STAGE_PLAYER, "player-state") { respawnedInternal(p, worldId, x, y, z) }

    private fun respawnedInternal(p: Player, worldId: UUID, x: Double, y: Double, z: Double) {
        val id = p.uniqueId
        val outer = overridingEventPlayer
        overridingEventPlayer = id
        try {
            core.respawn(id, worldId, x, y, z)
        } finally {
            overridingEventPlayer = outer
        }
        for (t in playerTimers) if (t.skipDead) t.resume(id)
    }

    /** The player died: the engine exits their areas, and timers that skip the dead pause them until respawn. */
    fun died(p: Player) = stage(STAGE_PLAYER, "player-state") {
        val id = p.uniqueId
        core.death(id)
        for (t in playerTimers) if (t.skipDead) t.pause(id)
    }

    private fun requestResync(id: UUID) {
        pendingResync.add(id)
        if (resyncScheduled || !running) return
        resyncScheduled = true
        plugin.server.scheduler.runTask(plugin, Runnable { resync() })
    }

    /** Feeds each noted player's actual position (a no-op when the engine already agrees). */
    internal fun resync() {
        resyncScheduled = false
        if (!running) return
        refreshTiming()
        stage(STAGE_RIDING, "resync") { resyncAll() }
    }

    private fun resyncAll() {
        val ids = pendingResync.toList()
        pendingResync.clear()
        for (id in ids) {
            val p = plugin.server.getPlayer(id) ?: continue
            val loc = p.location
            val world = loc.world ?: continue
            resyncs++
            core.position(id, world.uid, loc.x, loc.y, loc.z, true)
        }
    }

    // ---- riding state
    // Bukkit fires EntityMountEvent and EntityDismountEvent before the passenger is attached or detached, and another
    // plugin may still cancel them. Applying them there would run VEHICLE enters while isInsideVehicle is still false.
    // So a (dis)mount event only notes the player; at the next tick the service reads isInsideVehicle and applies what
    // really happened, with the rider's current position. Teleports and world changes re-read it on the spot.

    private val pendingRiding = LinkedHashSet<UUID>()
    private var ridingCheckScheduled = false

    /** Players waiting for their riding state to be re-read, for leak tests. */
    internal val pendingRidingCount: Int get() = pendingRiding.size

    /** A mount or dismount event for [p] (not cancelled): re-read their riding state next tick. */
    fun ridingEvent(p: Player) {
        if (core.state(p.uniqueId) == null) return
        pendingRiding.add(p.uniqueId)
        if (ridingCheckScheduled || !running) return
        ridingCheckScheduled = true
        plugin.server.scheduler.runTask(plugin, Runnable { applyRiding() })
    }

    /** Applies the riding changes noted since the last tick, then feeds each player's position. */
    internal fun applyRiding() {
        ridingCheckScheduled = false
        if (!running) return
        refreshTiming()
        stage(STAGE_RIDING, "riding") { applyRidingAll() }
    }

    private fun applyRidingAll() {
        val ids = pendingRiding.toList()
        pendingRiding.clear()
        for (id in ids) {
            val p = plugin.server.getPlayer(id) ?: continue
            val s = syncRiding(p) ?: continue
            p.getLocation(pollLocation)
            val world = pollLocation.world ?: continue
            core.position(s, world.uid, pollLocation.x, pollLocation.y, pollLocation.z, false)
        }
        pollLocation.world = null
    }

    /**
     * Makes the engine's riding flag match `isInsideVehicle` (one field read on Paper) and returns the player's
     * state, or null when the player isn't tracked. A change re-evaluates entering on the spot.
     */
    fun syncRiding(p: Player): PlayerState? {
        val s = core.state(p.uniqueId) ?: return null
        val riding = p.isInsideVehicle
        if (s.mounted != riding) {
            core.setMounted(s, riding)
            if (riding) ensureTicker()
        }
        return s
    }

    fun playerQuit(p: Player) = stage(STAGE_PLAYER, "player-state") {
        core.quit(p.uniqueId)
        for (t in playerTimers) t.removeInternal(p.uniqueId)
        clicks.forget(p.uniqueId)
    }

    private fun tick() {
        ticks++
        refreshTiming()
        val pr = probe
        val token = if (timing && pr != null) pr.start() else 0L
        try {
            pollMounted()
            runTimersTimed()
        } finally {
            if (token != 0L) pr!!.end("tick", token)
        }
        if (!tickerHasWork()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /**
     * Feeds every riding player's position with cause VEHICLE: covers passengers whose movement makes no
     * PlayerMoveEvent (camels, happy ghasts) and vehicles teleported with passengers. Reads into one reusable
     * Location. A player no longer inside a vehicle (a dismount event we didn't see) is corrected here.
     */
    private fun pollMounted() {
        val n = core.mountedCount
        if (n == 0) return
        if (!timing) {
            pollRiders(n)
            return
        }
        val pr = probe
        val token = if (pr != null) pr.start() else 0L
        val t0 = System.nanoTime()
        try {
            pollRiders(n)
        } finally {
            pollNanos += System.nanoTime() - t0
            if (token != 0L) pr!!.end("tick.mounted-poll", token)
        }
    }

    private fun runTimersTimed() {
        val pr = probe
        val token = if (timing && pr != null) pr.start() else 0L
        try {
            runTimers()
        } finally {
            if (token != 0L) pr!!.end("tick.timers", token)
        }
    }

    private fun pollRiders(n: Int) {
        val buffer = core.snapshotMounted()
        for (i in 0 until n) {
            val s = buffer[i]
            buffer[i] = null
            if (s == null || s.removed || !s.mounted) continue
            val p = s.player ?: continue
            if (!p.isInsideVehicle) {
                core.setMounted(s, false)
                continue
            }
            p.getLocation(pollLocation)
            val world = pollLocation.world ?: continue
            mountedPolls++
            core.position(s, world.uid, pollLocation.x, pollLocation.y, pollLocation.z, false)
        }
        pollLocation.world = null
    }

    /**
     * Runs the global timers due now and each per-player timer's due bucket. One call per server tick: a timer runs
     * at most once a tick and a late tick is never made up (no catch-up). No allocation.
     */
    internal fun runTimers() {
        val now = ticks
        // Snapshot both lists before any callback runs: a timer registered by a callback starts next tick.
        val globals = globalTimers
        val perPlayer = playerTimers
        for (t in globals) {
            if (t.closed || now < t.nextDue) continue
            // Normally now == nextDue. If the ticker ever skipped ticks, keep the phase and don't catch up.
            val late = now - t.nextDue
            t.nextDue = now + t.interval - (late % t.interval)
            val task = t.task
            guard(t) { task.run() }
        }
        for (t in perPlayer) if (!t.closed && now >= t.firstTick) t.fire(now)
    }

    /** The first tick after now whose phase is [phase] for [interval]: within 1..interval ticks. */
    private fun firstDue(phase: Int, interval: Int): Long {
        val base = ticks + 1
        return base + Math.floorMod(phase - base, interval.toLong())
    }

    fun removeTimer(t: TimerSub) {
        val i = globalTimers.indexOf(t)
        if (i < 0) return
        globalTimers = globalTimers.filterIndexed { j, _ -> j != i }.toTypedArray()
        globalPhases.release(t.interval, t.phaseBucket)
    }

    fun removePlayerTimer(t: PlayerTimerSub) {
        val i = playerTimers.indexOf(t)
        if (i < 0) return
        playerTimers = playerTimers.filterIndexed { j, _ -> j != i }.toTypedArray()
        playerPhases.release(t.interval, t.offsetBucket)
    }

    /** A world unloaded: its areas close (no player can be in it, so no exits fire) and its index goes. */
    fun worldUnloaded(worldId: UUID) {
        for (reg in index.regsInWorld(worldId)) (reg.link as? SubBase)?.close()
        for (sub in clicks.blockSubsInWorld(worldId)) sub.close()
        for (sub in removals.subsInWorld(worldId)) sub.close()
    }

    // ---------------------------------------------------------------- handler calls

    /**
     * Runs one handler call for [sub]: counts it, isolates its exceptions, and times it while timing is on.
     * Never lets a handler's exception reach the engine or Bukkit (VM errors such as OutOfMemoryError excepted).
     */
    inline fun guard(sub: SubBase, cb: Int = SubBase.CB_MAIN, block: () -> Unit) {
        sub.calls++
        handlerCalls++
        if (!timing) {
            try {
                block()
            } catch (e: VirtualMachineError) {
                throw e
            } catch (t: Throwable) {
                handlerFailed(sub, t, cb)
            }
            return
        }
        val pr = probe
        val token = if (pr != null) pr.start() else 0L
        val t0 = System.nanoTime()
        try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            handlerFailed(sub, t, cb)
        } finally {
            val nanos = System.nanoTime() - t0
            sub.nanos += nanos
            sub.timedCalls++
            if (nanos >= SLOW_HANDLER_NANOS) handlerSlow(sub, cb, token != 0L)
            // The section name is resolved once per subscription and capture (TriggerDiagnostics.name).
            if (token != 0L) pr!!.end(diag.name(sub, TriggerDiagnostics.SECTION, cb), token)
        }
    }

    /** A handler call took [SLOW_HANDLER_NANOS] or more: counted, and during a capture under its own name. */
    fun handlerSlow(sub: SubBase, cb: Int, recorded: Boolean) {
        sub.slow++
        slowCalls++
        if (recorded) probe?.count(diag.name(sub, TriggerDiagnostics.SLOW, cb), 1)
    }

    /** Counts during a capture: [what] ([TriggerDiagnostics.ERRORS], ...) for [sub]'s callback [cb]. Rare paths only. */
    private fun countFor(sub: SubBase, what: Int, cb: Int, n: Long) {
        val pr = probe ?: return
        if (!pr.on()) return
        if (!recording) refreshTiming() // a capture that began since the last refresh: new names
        pr.count(diag.name(sub, what, cb), n)
    }

    /** Counts the error and logs it with the stack trace at most once a minute per subscription. */
    fun handlerFailed(sub: SubBase, t: Throwable, cb: Int = SubBase.CB_MAIN) {
        sub.errors++
        handlerErrors++
        countFor(sub, TriggerDiagnostics.ERRORS, cb, 1)
        val now = clock()
        if (sub.lastErrorLogMillis != Long.MIN_VALUE && now - sub.lastErrorLogMillis < ERROR_LOG_INTERVAL_MILLIS) {
            sub.suppressedErrors++
            return
        }
        val suppressed = sub.suppressedErrors
        sub.lastErrorLogMillis = now
        sub.suppressedErrors = 0
        val more = if (suppressed > 0) " ($suppressed more since the last report)" else ""
        plugin.logger.log(
            Level.SEVERE,
            "Trigger ${sub.id} (owner ${sub.owner.name}) threw; it stays subscribed$more",
            t,
        )
    }

    // ---------------------------------------------------------------- EngineSink

    // Queued engine events reach a handler only while its owner is enabled. Enters and exits are delivered even
    // after an explicit close (so they pair up), and such a subscription has left its owner's set, so the owner's
    // disable can't silence it; this check does. It also keeps exits from closes made in the owner's own onDisable
    // (Bukkit marks a plugin disabled before calling onDisable) out of the disabling plugin.

    override fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val sub = reg.link as? AreaSub ?: return
        val p = state.player ?: return
        if (!sub.owner.isEnabled) return
        guard(sub, SubBase.CB_ENTER) { sub.handler.onEnter(p, reg.area, cause) }
    }

    override fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val sub = reg.link as? AreaSub ?: return
        val p = state.player ?: return
        if (!sub.owner.isEnabled) return
        guard(sub, SubBase.CB_EXIT) { sub.handler.onExit(p, reg.area, cause) }
    }

    override fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val sub = reg.link as? AreaSub ?: return
        val p = state.player ?: return
        if (!sub.owner.isEnabled) return
        guard(sub, SubBase.CB_STEP) { sub.handler.onStep(p, reg.area, cause) }
    }

    override fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean) {
        val sub = watch.link as? WatchSub ?: return
        val p = state.player ?: return
        val h = watch.handler ?: return
        if (!sub.owner.isEnabled) return
        guard(sub) { h.onStep(p, from, to, mounted) }
    }

    override fun blockWatch(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch) {
        val sub = watch.link as? WatchSub ?: return
        val p = state.player ?: return
        if (!sub.owner.isEnabled) return
        val h = watch.handler ?: return
        val world = plugin.server.getWorld(worldId) ?: return
        guard(sub) { h.onBlock(p, world.getBlockAt(x, y, z), match) }
    }

    override fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material {
        val world = state.worldId?.let { plugin.server.getWorld(it) } ?: return Material.AIR
        return world.getType(x, y, z)
    }

    override fun predicateFailed(reg: AreaReg, error: Throwable) {
        val sub = reg.link as? SubBase ?: return
        handlerFailed(sub, error, SubBase.CB_PREDICATE)
    }

    override fun watchEnded(watch: Watch) {
        (watch.link as? SubBase)?.endedByEngine()
    }

    override fun beforeDelivery(): Long {
        refreshTiming()
        val pr = probe
        if (!timing || pr == null) return 0L
        return pr.start()
    }

    override fun afterDelivery(token: Long) {
        if (token != 0L) probe?.end("drain", token)
    }

    override fun selfSkipped(subject: Any, kind: Int) {
        val sub = linkOf(subject) ?: return
        countFor(sub, TriggerDiagnostics.SELF_SKIPPED, if (kind == EventQueue.AREA_STEP) SubBase.CB_STEP else SubBase.CB_MAIN, 1)
    }

    private fun linkOf(subject: Any?): SubBase? = when (subject) {
        is AreaReg -> subject.link
        is Watch -> subject.link
        else -> null
    } as? SubBase

    /** Rate limit of the loop warning: like handler errors, once a minute with a count of the rest. */
    private var lastDropLogMillis = Long.MIN_VALUE
    private var dropsSinceLog = 0L

    override fun eventsDropped(count: Int, lastSubject: Any?) {
        linkOf(lastSubject)?.let { countFor(it, TriggerDiagnostics.DROPPED_AFTER, SubBase.CB_ANY, count.toLong()) }
        val now = clock()
        if (lastDropLogMillis != Long.MIN_VALUE && now - lastDropLogMillis < ERROR_LOG_INTERVAL_MILLIS) {
            dropsSinceLog += count
            return
        }
        val more = if (dropsSinceLog > 0) " ($dropsSinceLog more dropped since the last report)" else ""
        lastDropLogMillis = now
        dropsSinceLog = 0
        val sub = linkOf(lastSubject)
        plugin.logger.severe(
            "Triggers dropped $count queued events: one event set off more than " +
                "${com.sneakyutils.triggers.engine.EventQueue.DEFAULT_MAX_PER_DRAIN} handler calls. The last one ran " +
                "was ${sub?.id ?: "unknown"}; its handlers probably move players back and forth between areas.$more",
        )
    }

    // ---------------------------------------------------------------- Triggers: areas and watches

    override fun area(owner: Plugin, area: Area, handler: AreaHandler, options: AreaOptions): Subscription {
        checkCall(owner)
        // Refused inside an area predicate: checked before anything is registered, so a refusal leaves nothing.
        core.checkMutable("register an area")
        val reg = AreaReg(area, options)
        val sub = register(AreaSub(this, owner, reg, handler))
        try {
            refreshTiming()
            stage(STAGE_INDEX, "area-index.add") { core.addArea(reg) }
        } catch (e: RuntimeException) {
            if (!reg.active) {
                sub.closed = true
                unregister(sub)
            }
            throw e
        }
        if (!denseWarned && index.maxChunkListing > DENSE_CHUNK_AREAS) {
            denseWarned = true
            plugin.logger.warning(
                "A chunk now lists ${index.maxChunkListing} trigger areas (the latest from ${owner.name}): every block " +
                    "step there checks each one. See /triggers stats.",
            )
        }
        return sub
    }

    override fun watchSteps(owner: Plugin, player: Player, handler: StepHandler, options: WatchOptions): Subscription =
        addWatch(owner, player, StepWatch(player.uniqueId, handler, options), WatchSub.STEP)

    override fun watchBlockUnder(
        owner: Plugin,
        player: Player,
        materials: Set<Material>,
        handler: BlockUnderHandler,
        options: WatchOptions,
    ): Subscription {
        require(materials.isNotEmpty()) { "materials must not be empty" }
        val adapter = BlockWatchHandler { p, block, _ -> handler.onBlockUnder(p, block) }
        val watch = BlockWatch(player.uniqueId, BlockOffset.BELOW, materials, adapter, false, options)
        return addWatch(owner, player, watch, WatchSub.UNDER)
    }

    override fun watchBlock(
        owner: Plugin,
        player: Player,
        at: BlockOffset,
        materials: Set<Material>,
        handler: BlockWatchHandler,
        options: WatchOptions,
    ): Subscription {
        require(materials.isNotEmpty()) { "materials must not be empty" }
        val watch = BlockWatch(player.uniqueId, at, materials, handler, true, options)
        return addWatch(owner, player, watch, WatchSub.BLOCK)
    }

    private fun addWatch(owner: Plugin, player: Player, watch: Watch, kind: String): Subscription {
        checkCall(owner)
        val sub = WatchSub(this, owner, kind, watch)
        if (core.state(player.uniqueId) == null && !watch.options.keepOnQuit && !player.isOnline) {
            // Offline without keepOnQuit: it would end on the quit that already happened. An online player not tracked
            // yet (a join handler that runs before ours) gets it like a keepOnQuit watch: the join picks it up.
            sub.closed = true
            return sub
        }
        core.checkMutable("add a watch") // before anything is registered (see area())
        register(sub)
        try {
            core.addWatch(watch)
        } catch (e: RuntimeException) {
            if (!watch.active) {
                sub.closed = true
                unregister(sub)
            }
            throw e
        }
        return sub
    }

    // ---------------------------------------------------------------- Triggers: not yet built

    // ---------------------------------------------------------------- Triggers: timers

    private fun checkInterval(intervalTicks: Int) {
        require(intervalTicks in 1..BucketBalancer.MAX_INTERVAL) {
            "intervalTicks must be 1..${BucketBalancer.MAX_INTERVAL}, got $intervalTicks"
        }
    }

    override fun every(owner: Plugin, intervalTicks: Int, task: Runnable): Subscription =
        every(owner, intervalTicks, task, TimerOptions.DEFAULT)

    override fun every(owner: Plugin, intervalTicks: Int, task: Runnable, options: TimerOptions): Subscription {
        checkCall(owner)
        checkInterval(intervalTicks)
        val bucket = globalPhases.acquire(intervalTicks)
        val phase = globalPhases.phaseOf(intervalTicks, bucket)
        val sub = register(TimerSub(this, owner, intervalTicks, task, bucket, firstDue(phase, intervalTicks), options.label))
        globalTimers += sub
        ensureTicker()
        return sub
    }

    override fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, scope: PlayerScope): PlayerTimer =
        everyForPlayers(owner, intervalTicks, handler, PlayerTimerOptions.builder().scope(scope).build())

    override fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, options: PlayerTimerOptions): PlayerTimer {
        checkCall(owner)
        checkInterval(intervalTicks)
        val bucket = playerPhases.acquire(intervalTicks)
        val offset = playerPhases.phaseOf(intervalTicks, bucket)
        val scope = options.scope
        val sub = register(
            PlayerTimerSub(this, owner, intervalTicks, handler, scope, bucket, offset, ticks + 1, options.skipDead, options.label),
        )
        if (scope == PlayerScope.ALL_ONLINE) for (p in plugin.server.onlinePlayers) sub.addInternal(p)
        playerTimers += sub
        ensureTicker()
        return sub
    }

    // ---------------------------------------------------------------- Triggers: blocks and entities

    private fun requirePackable(block: BlockKey) {
        require(block.isPackable) { "$block is outside the packable block range" }
    }

    private fun requireBlockMaterial(material: Material) {
        require(!material.isLegacy && material.isBlock) { "$material is not a block material" }
    }

    override fun onBlockClick(owner: Plugin, block: BlockKey, handler: BlockClickHandler, options: ClickOptions): Subscription {
        checkCall(owner)
        requirePackable(block)
        val sub = register(BlockSub(this, owner, BlockSub.CLICK, block, null, handler, null, options))
        clicks.add(sub)
        return sub
    }

    override fun onBlockType(owner: Plugin, material: Material, handler: BlockClickHandler, options: ClickOptions): Subscription {
        checkCall(owner)
        requireBlockMaterial(material)
        val sub = register(BlockSub(this, owner, BlockSub.CLICK_TYPE, null, material, handler, null, options))
        clicks.add(sub)
        return sub
    }

    override fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler): Subscription =
        onBlockBreak(owner, block, handler, BreakOptions.DEFAULT)

    override fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler): Subscription =
        onBlockBreakType(owner, material, handler, BreakOptions.DEFAULT)

    override fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler, options: BreakOptions): Subscription {
        checkCall(owner)
        requirePackable(block)
        val sub = register(BlockSub(this, owner, BlockSub.BREAK, block, null, null, handler, ClickOptions.DEFAULT, options))
        clicks.add(sub)
        return sub
    }

    override fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler, options: BreakOptions): Subscription {
        checkCall(owner)
        requireBlockMaterial(material)
        val sub = register(BlockSub(this, owner, BlockSub.BREAK_TYPE, null, material, null, handler, ClickOptions.DEFAULT, options))
        clicks.add(sub)
        return sub
    }

    override fun onBlockRemoved(owner: Plugin, block: BlockKey, handler: BlockRemovedHandler, options: BlockRemovedOptions): Subscription {
        checkCall(owner)
        requirePackable(block)
        val sub = register(RemovedSub(this, owner, block, handler, options.label))
        removals.add(sub)
        ensureRemovalListener()
        return sub
    }

    /**
     * Registers the removal listener once. Until a plugin watches a removal, SneakyUtils listens to none of those events,
     * so Paper doesn't build them (it skips `BlockDestroyEvent` without listeners) and fluid flow costs nothing extra.
     */
    private fun ensureRemovalListener() {
        if (removalListener != null || !running) return
        val l = BlockRemovedListener(this)
        removalListener = l
        plugin.server.pluginManager.registerEvents(l, plugin)
        // Paper's old TNTPrimeEvent is deprecated for removal. Bukkit logs and skips a listener whose event class is gone,
        // so check that it really listens; without it, fire-primed TNT and dragon breaks come from the earlier events.
        try {
            val old = OldTntPrimeListener(this)
            plugin.server.pluginManager.registerEvents(old, plugin)
            @Suppress("DEPRECATION", "removal")
            val listening = com.destroystokyo.paper.event.block.TNTPrimeEvent.getHandlerList().registeredListeners
                .any { it.listener === old }
            if (listening) {
                oldPrimeListener = old
                l.oldPrime = true
            }
        } catch (e: LinkageError) {
            plugin.logger.info("Paper's old TNTPrimeEvent is gone; block removal watches report fire-primed TNT and " +
                "dragon breaks from the events before it")
        }
    }

    override fun onEntityClick(owner: Plugin, match: EntityMatch, handler: EntityClickHandler, options: ClickOptions): Subscription {
        checkCall(owner)
        require(options.sides != ClickSide.PHYSICAL) { "entity clicks have no PHYSICAL side" }
        val sub = register(EntitySub(this, owner, match, handler, options))
        clicks.add(sub)
        return sub
    }

    override fun labelled(label: String): Triggers = LabelledTriggers(this, com.sneakyutils.triggers.TriggerLabels.check(label)!!)

    override fun loadTable(owner: Plugin, file: File, actions: ActionResolver): TableLoadResult {
        checkCall(owner)
        return tables.load(owner, file, actions)
    }

    // ---------------------------------------------------------------- Triggers: stats

    /** Always-on counters by name. Every name is a fixed label. */
    fun counters(): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        m["events.move"] = moveEvents
        m["events.teleport"] = teleportEvents
        m["polls.mounted"] = mountedPolls
        m["steps"] = core.steps
        m["steps.noop"] = core.noopSteps
        m["steps.long"] = core.longMoves
        m["area.enters"] = core.enters
        m["area.exits"] = core.exits
        m["area.pass-through"] = core.passThroughs
        m["handler.calls"] = handlerCalls
        m["handler.errors"] = handlerErrors
        m["handler.slow"] = slowCalls
        m["queue.dropped"] = core.queue.dropped
        m["queue.self-triggered"] = core.queue.selfTriggerDrops
        m["reentrant.dropped"] = core.reentrantDrops
        m["predicate.errors"] = core.predicateErrors
        m["ticks"] = ticks
        m["teleport.resyncs"] = resyncs
        m["events.teleport.riding"] = riderTeleports
        m["polls.mounted.nanos"] = pollNanos
        m["events.interact"] = clicks.interactEvents
        m["events.break"] = clicks.breakEvents
        m["events.entity-click"] = clicks.entityEvents
        m["events.block-removed"] = removals.events
        m["block-removed.lookups"] = removals.lookups
        m["block-removed.neighbour-checks"] = removals.neighbourChecks
        m["block-removed.neighbour-removals"] = removals.neighbourRemovals
        return m
    }

    override fun stats(): TriggerStats {
        requireMainThread()
        val subs = subscriptions()
        val byKind = java.util.TreeMap<String, Int>()
        val byOwner = java.util.TreeMap<String, java.util.TreeMap<String, Int>>()
        val list = ArrayList<SubscriptionStats>(subs.size)
        for (s in subs) {
            byKind.merge(s.kind, 1, Int::plus)
            byOwner.getOrPut(s.owner.name) { java.util.TreeMap() }.merge(s.kind, 1, Int::plus)
            list += SubscriptionStats(s.id, s.owner.name, s.kind, s.calls, s.errors, s.nanos, s.label, s.slow)
        }
        return TriggerStats(
            byKind = byKind,
            byOwner = byOwner,
            areaChunks = index.chunkEntries,
            allOnlineTimers = playerTimers.count { it.scope == PlayerScope.ALL_ONLINE },
            trackedPlayers = core.playerCount,
            mountedPlayers = core.mountedCount,
            counters = counters(),
            subscriptions = list,
            timing = timing,
            warnings = warnings(),
        )
    }

    /** See [TriggerStats.warnings]. Scans the indexes: for the command and stats, never a hot path. */
    fun warnings(): List<String> {
        val out = ArrayList<String>()
        index.densestChunk()?.let { (world, key, n) ->
            if (n > DENSE_CHUNK_AREAS) {
                val name = plugin.server.getWorld(world)?.name ?: world.toString()
                out += "chunk ${Geometry.chunkX(key)},${Geometry.chunkZ(key)} in $name lists $n areas: every block step " +
                    "there checks each (over $DENSE_CHUNK_AREAS costs microseconds a step; split or merge them)"
            }
        }
        val watches = core.maxWatchesPerPlayer()
        if (watches > MANY_WATCHES) out += "a player has $watches watches: each block step calls every one"
        clicks.densest()?.let { (what, n) ->
            if (n > MANY_PER_KEY) out += "$what has $n subscriptions: every click there runs through all of them"
        }
        removals.densest()?.let { (what, n) ->
            if (n > MANY_PER_KEY) out += "$what has $n subscriptions: every removal there runs through all of them"
        }
        if (core.queue.dropped > 0) out += "${core.queue.dropped} events were dropped by handler loops (see the log)"
        if (core.queue.selfTriggerDrops > 0) {
            out += "${core.queue.selfTriggerDrops} step calls were skipped because the handler itself caused them (a " +
                "handler moving its own player; the re-entry guard)"
        }
        if (core.reentrantDrops > 0) {
            out += "${core.reentrantDrops} position reports came from inside area predicates and were dropped"
        }
        if (core.predicateErrors > 0) out += "area predicates threw ${core.predicateErrors} times (see the log)"
        if (handlerErrors > 0) out += "handlers threw $handlerErrors times (see the log; /triggers top shows which)"
        return out
    }

    companion object {
        const val ERROR_LOG_INTERVAL_MILLIS = 60_000L

        /** A handler call this long or longer counts as slow (`handler.slow`, `/triggers top`). 1 ms. */
        const val SLOW_HANDLER_NANOS = 1_000_000L

        // Engine stages (see stage()); one bit each.
        const val STAGE_MOVE = 1
        const val STAGE_TELEPORT = 2
        const val STAGE_PLAYER = 4
        const val STAGE_RIDING = 8
        const val STAGE_CLICK = 16
        const val STAGE_INDEX = 32
        const val STAGE_REMOVED = 64

        /** Every subscription kind, for the `subscriptions.by-kind.<kind>` levels (reported as 0 when none). */
        val KINDS = listOf(
            AreaSub.KIND, WatchSub.STEP, WatchSub.UNDER, WatchSub.BLOCK, TimerSub.KIND, PlayerTimerSub.KIND,
            PlayerTimerSub.KIND_ALL, BlockSub.CLICK, BlockSub.CLICK_TYPE, BlockSub.BREAK, BlockSub.BREAK_TYPE, EntitySub.KIND,
            RemovedSub.KIND,
        )

        /** Warning thresholds (see [warnings]). 256 areas in one chunk cost about 1 us a block step on a desktop. */
        const val DENSE_CHUNK_AREAS = 256
        const val MANY_WATCHES = 16
        const val MANY_PER_KEY = 64
    }
}
