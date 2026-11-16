package com.sneakyutils.triggers

/**
 * A snapshot of the trigger service, from [Triggers.stats] and `/triggers`. Building it allocates; don't call it on
 * a hot path. Counters are longs and count since SneakyUtils enabled.
 */
class TriggerStats @JvmOverloads constructor(
    /** Active subscriptions by kind (`area`, `step-watch`, `block-under`, `timer`, `player-timer`, ...). */
    val byKind: Map<String, Int>,
    /** Active subscriptions by owner plugin name, then kind. */
    val byOwner: Map<String, Map<String, Int>>,
    /** Chunk entries in the area index (an area spanning n chunks counts n). */
    val areaChunks: Int,
    /** Per-player timers with [PlayerScope.ALL_ONLINE]: the pattern to avoid, so it is flagged. */
    val allOnlineTimers: Int,
    /** Players tracked by the movement engine, and how many of them ride something. */
    val trackedPlayers: Int,
    val mountedPlayers: Int,
    /** Always-on counters by name (`events.move`, `steps`, `steps.noop`, `area.enters`, ...). */
    val counters: Map<String, Long>,
    /** Every active subscription, in no particular order. */
    val subscriptions: List<SubscriptionStats>,
    /** Whether handler time is being measured (a SneakyDiagnostics capture or `/triggers profile on`). */
    val timing: Boolean,
    /**
     * Layouts that make each event cost more than it should (a chunk listing hundreds of areas, a player with many
     * watches, many subscriptions on one block or type) and signs of misuse (dropped events, failing handlers), as
     * sentences for `/triggers`. Empty when all is well.
     */
    val warnings: List<String> = emptyList(),
) {
    fun counter(name: String): Long = counters[name] ?: 0L
    fun count(kind: String): Int = byKind[kind] ?: 0

    override fun toString(): String =
        "TriggerStats(byKind=$byKind, areaChunks=$areaChunks, allOnlineTimers=$allOnlineTimers, " +
            "trackedPlayers=$trackedPlayers, mountedPlayers=$mountedPlayers, counters=$counters)"
}

/** One subscription's numbers. [nanos] is handler time measured while timing was on, 0 otherwise. */
class SubscriptionStats(
    val id: String,
    val owner: String,
    val kind: String,
    val calls: Long,
    val errors: Long,
    val nanos: Long,
) {
    override fun toString(): String = "SubscriptionStats($id, calls=$calls, errors=$errors, nanos=$nanos)"
}
