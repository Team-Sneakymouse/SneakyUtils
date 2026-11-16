package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.AreaHandler
import com.sneakyutils.triggers.Subscription
import com.sneakyutils.triggers.engine.AreaReg
import com.sneakyutils.triggers.engine.Watch
import org.bukkit.plugin.Plugin
import java.util.Locale

/**
 * Base of every subscription: identity, owner, counters, and the close protocol.
 *
 * Closing has two paths: [close] (the owner closed it: area exits fire with UNREGISTER) and
 * [TriggerService.closeOwner] / shutdown (silent: nothing fires, queued events for it are dropped). Either way the
 * subscription leaves the registry exactly once ([state] goes from ACTIVE to CLOSED once).
 */
internal abstract class SubBase(
    @JvmField val service: TriggerService,
    final override val owner: Plugin,
    @JvmField val kind: String,
) : Subscription {
    final override val id: String = "${owner.name}:$kind#${service.nextSequence()}"

    @JvmField var closed = false

    // Always-on counters. Longs: at a million calls a second they last 290,000 years.
    @JvmField var calls = 0L
    @JvmField var errors = 0L

    /** Handler nanoseconds measured while timing was on. */
    @JvmField var nanos = 0L

    // Error log rate limit: one report a minute, counting the ones suppressed in between.
    @JvmField var lastErrorLogMillis = Long.MIN_VALUE
    @JvmField var suppressedErrors = 0L

    /** SneakyDiagnostics section name: a fixed label per owner and kind (never per subscription). */
    @JvmField val probeSection: String = "sub.${owner.name.lowercase(Locale.ROOT)}.$kind"

    final override val active: Boolean get() = !closed

    final override fun close() {
        service.requireMainThread()
        if (closed) return
        // Checked before anything changes: a refused close (inside an area predicate) leaves the subscription active,
        // registered and closable later.
        checkRelease()
        closed = true
        service.unregister(this)
        release(silent = false)
    }

    /** Throws if [release] can't run now. Called by [close] before it changes anything. */
    protected open fun checkRelease() {}

    /** Silent close for owner disable and shutdown; the registry entry is removed by the caller. */
    fun dispose() {
        if (closed) return
        closed = true
        release(silent = true)
    }

    /** The engine already removed the underlying watch (quit, world change): just leave the registry. */
    fun endedByEngine() {
        if (closed) return
        closed = true
        service.unregister(this)
    }

    /** Detach from the engine/index. [silent]: fire nothing and drop queued events. */
    protected abstract fun release(silent: Boolean)

    override fun toString(): String = "$id${if (closed) " (closed)" else ""}"
}

internal class AreaSub(service: TriggerService, owner: Plugin, @JvmField val reg: AreaReg, @JvmField val handler: AreaHandler) :
    SubBase(service, owner, KIND) {
    init {
        reg.link = this
    }

    override fun checkRelease() = service.core.checkMutable("close an area")

    override fun release(silent: Boolean) = service.core.removeArea(reg, silent)

    companion object {
        const val KIND = "area"
    }
}

internal class WatchSub(service: TriggerService, owner: Plugin, kind: String, @JvmField val watch: Watch) :
    SubBase(service, owner, kind) {
    init {
        watch.link = this
    }

    override fun checkRelease() = service.core.checkMutable("close a watch")

    override fun release(silent: Boolean) = service.core.removeWatch(watch, silent)

    companion object {
        const val STEP = "step-watch"
        const val UNDER = "block-under"
        const val BLOCK = "block-watch"
    }
}
