package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import org.bukkit.Material
import java.util.UUID

/**
 * Where the engine delivers what it decided. The Bukkit side ([com.sneakyutils.triggers.service.TriggerService])
 * calls the consumers' handlers inside its error guard; pure engine tests record the calls.
 *
 * Every call happens from [EventQueue.drain], never during evaluation, so a handler may move, teleport, kick or
 * kill the player, or subscribe and close, and the engine stays consistent: such calls change state at once and
 * queue their own events behind the current ones.
 */
internal interface EngineSink {
    fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause)
    fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause)
    /** A block step inside [reg]; [cause] is MOVE, VEHICLE or TELEPORT (see [AreaCause]). */
    fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause)
    fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean)
    /** [worldId] is the world the block was read in, captured when queued (the player may move on before delivery). */
    fun blockWatch(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch)

    /** The block type at a position in the player's world, for block watches. Called during evaluation. */
    fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material

    /** A custom area's predicate threw during evaluation; the engine treated the position as outside. */
    fun predicateFailed(reg: AreaReg, error: Throwable)

    /** The engine ended a watch itself (quit or world change). The sink updates the subscription; it must not
     * call back into the engine to remove it. */
    fun watchEnded(watch: Watch)

    /**
     * The queue dropped [count] events because one drain exceeded its limit (a handler loop). [lastSubject] is the
     * area or watch of the last event delivered before the cut, most likely part of the loop.
     */
    fun eventsDropped(count: Int, lastSubject: Any?)

    /** A drain with events is about to deliver (once per drain, never on the no-op path). */
    fun beforeDelivery() {}
}

/**
 * One queued delivery. Instances are pooled by [EventQueue] and reused: fields are cleared after delivery so the
 * queue holds no player or subscription after it drains.
 *
 * Everything a handler receives is captured here when the event is queued, never read from the player's state at
 * delivery: an earlier handler in the same drain may have moved the player (even to another world). Only the
 * delivery rules (closed subscription, quit player) are checked at delivery.
 */
internal class QueuedEvent {
    @JvmField var kind: Int = 0
    @JvmField var state: PlayerState? = null
    @JvmField var reg: AreaReg? = null
    @JvmField var watch: Watch? = null
    @JvmField var cause: AreaCause? = null
    @JvmField var from: BlockKey? = null
    @JvmField var to: BlockKey? = null
    @JvmField var mounted: Boolean = false
    @JvmField var match: BlockMatch? = null
    @JvmField var worldId: UUID? = null
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var z: Int = 0

    /** The delivery (an index in the drain's log) whose handler queued this event, or -1 (see [EventQueue]). */
    @JvmField var causedBy: Int = -1

    fun clear() {
        state = null
        reg = null
        watch = null
        cause = null
        from = null
        to = null
        match = null
        worldId = null
    }
}

/**
 * FIFO of engine events, delivered by the outermost [drain]. Re-entrant engine calls made by handlers only append,
 * and the running drain delivers their events afterwards, in order. This keeps every player's enter and exit for an
 * area alternating even when a handler teleports the player, and bounds the stack depth.
 *
 * Delivery rules:
 * - Enter and exit are delivered even after the area's subscription closed explicitly (enter and exit pair up),
 *   but not after it was silenced (owner or SneakyUtils disable).
 * - Steps, area steps and block-watch events are skipped when their subscription is no longer active or the player
 *   quit since they were queued.
 * - A step, area step or block-watch event that its own subscription's handler caused for the same player, directly
 *   or through other handlers it set off in this drain, is dropped (a jump pad's step handler moving the player
 *   doesn't hear about that move). MagicSpells dropped a passive re-triggered during its own run the same way. Enters
 *   and exits are never dropped this way: they pair up.
 * - One drain delivers at most [maxPerDrain] events; the rest are dropped and reported (a handler loop such as
 *   enter-teleports-out, exit-teleports-in).
 *
 * The guard keeps a log of the drain's deliveries: subject, player and the delivery that caused it. An event queued
 * while a handler runs records that handler's delivery as its cause; checking an event walks its causes, which is
 * nothing for events no handler caused. The log is cleared when the drain ends.
 */
internal class EventQueue(private val sink: EngineSink, private val maxPerDrain: Int = DEFAULT_MAX_PER_DRAIN) {
    private var buffer = Array(16) { QueuedEvent() }
    private var head = 0
    private var tail = 0
    private var draining = false

    /** Set by shutdown: the running drain stops and later drains do nothing. */
    @JvmField var closed = false

    val pending: Int get() = tail - head

    /** Events dropped by the per-drain limit, since creation. */
    var dropped: Long = 0
        private set

    /** Step, area-step and block events dropped because their own subscription's handler caused them. */
    var selfTriggerDrops: Long = 0
        private set

    // The drain's delivery log (see the class comment), reused between drains.
    private var logSubject = arrayOfNulls<Any>(16)
    private var logPlayer = arrayOfNulls<UUID>(16)
    private var logCause = IntArray(16)
    private var logSize = 0

    /** The delivery whose handler is running now, or -1. Drains don't nest, so there is at most one. */
    private var currentCause = -1

    private fun next(kind: Int, state: PlayerState): QueuedEvent {
        if (tail == buffer.size) grow()
        val e = buffer[tail++]
        e.kind = kind
        e.state = state
        e.causedBy = currentCause
        return e
    }

    private fun log(subject: Any, player: UUID, cause: Int): Int {
        if (logSize == logSubject.size) {
            val n = logSize * 2
            logSubject = logSubject.copyOf(n)
            logPlayer = logPlayer.copyOf(n)
            logCause = logCause.copyOf(n)
        }
        logSubject[logSize] = subject
        logPlayer[logSize] = player
        logCause[logSize] = cause
        return logSize++
    }

    /** Whether [subject]'s own delivery for [player] is among the causes starting at [cause]. */
    private fun causedBySelf(cause: Int, subject: Any, player: UUID): Boolean {
        var i = cause
        while (i >= 0) {
            if (logSubject[i] === subject && logPlayer[i] == player) return true
            i = logCause[i]
        }
        return false
    }

    private fun clearLog() {
        for (i in 0 until logSize) {
            logSubject[i] = null
            logPlayer[i] = null
        }
        logSize = 0
        currentCause = -1
    }

    private fun grow() {
        if (head > 0 && head >= buffer.size / 2) {
            // Compact: move the live range to the front (rotating the pooled objects, no allocation).
            val live = tail - head
            val rotated = arrayOfNulls<QueuedEvent>(buffer.size)
            for (i in 0 until live) rotated[i] = buffer[head + i]
            var j = live
            for (i in 0 until head) rotated[j++] = buffer[i]
            for (i in tail until buffer.size) rotated[j++] = buffer[i]
            @Suppress("UNCHECKED_CAST")
            buffer = rotated as Array<QueuedEvent>
            head = 0
            tail = live
            return
        }
        val old = buffer
        buffer = Array(old.size * 2) { if (it < old.size) old[it] else QueuedEvent() }
    }

    fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val e = next(ENTER, state)
        e.reg = reg
        e.cause = cause
    }

    fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val e = next(EXIT, state)
        e.reg = reg
        e.cause = cause
    }

    fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause) {
        val e = next(AREA_STEP, state)
        e.reg = reg
        e.cause = cause
    }

    fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean) {
        val e = next(STEP, state)
        e.watch = watch
        e.from = from
        e.to = to
        e.mounted = mounted
    }

    fun block(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch) {
        val e = next(BLOCK, state)
        e.watch = watch
        e.match = match
        e.worldId = worldId
        e.x = x
        e.y = y
        e.z = z
    }

    /** Delivers queued events unless a drain is already running further up the stack (that one delivers them). */
    fun drain() {
        if (draining || head == tail) return
        draining = true
        sink.beforeDelivery()
        try {
            var delivered = 0
            while (head < tail && !closed) {
                if (delivered == maxPerDrain) {
                    val n = tail - head
                    dropped += n
                    discard()
                    sink.eventsDropped(n, if (logSize > 0) logSubject[logSize - 1] else null)
                    break
                }
                val e = buffer[head++]
                delivered++
                deliver(e)
            }
        } finally {
            discard()
            clearLog()
            draining = false
        }
    }

    /** Drops every queued event without delivering it. */
    fun discard() {
        for (i in head until tail) buffer[i].clear()
        head = 0
        tail = 0
    }

    /**
     * Reads the event into locals and clears it before calling the sink: a handler may queue new events, and the
     * pooled object may be reused for one of them while the handler runs.
     */
    private fun deliver(e: QueuedEvent) {
        val kind = e.kind
        val s = e.state!!
        val reg = e.reg
        val watch = e.watch
        val cause = e.cause
        val from = e.from
        val to = e.to
        val mounted = e.mounted
        val match = e.match
        val worldId = e.worldId
        val x = e.x
        val y = e.y
        val z = e.z
        val causedBy = e.causedBy
        e.clear()
        val subject: Any = reg ?: watch!!
        val deliverable = when (kind) {
            ENTER, EXIT -> !reg!!.silenced
            AREA_STEP -> reg!!.active && !s.removed
            else -> watch!!.active && !s.removed
        }
        if (!deliverable) return
        if (kind >= AREA_STEP && causedBy >= 0 && causedBySelf(causedBy, subject, s.uuid)) {
            selfTriggerDrops++
            return
        }
        currentCause = log(subject, s.uuid, causedBy)
        try {
            when (kind) {
                ENTER -> sink.enter(s, reg!!, cause!!)
                EXIT -> sink.exit(s, reg!!, cause!!)
                AREA_STEP -> sink.areaStep(s, reg!!, cause!!)
                STEP -> sink.step(s, watch as StepWatch, from!!, to!!, mounted)
                BLOCK -> sink.blockWatch(s, watch as BlockWatch, worldId!!, x, y, z, match!!)
            }
        } finally {
            currentCause = -1
        }
    }

    companion object {
        const val ENTER = 1
        const val EXIT = 2
        const val AREA_STEP = 3
        const val STEP = 4
        const val BLOCK = 5

        /** Far above any real cascade (100 players x a few areas), low enough to stop a handler loop quickly. */
        const val DEFAULT_MAX_PER_DRAIN = 10_000
    }
}
