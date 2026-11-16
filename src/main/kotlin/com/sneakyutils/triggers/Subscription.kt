package com.sneakyutils.triggers

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * A registration with [Triggers]. Owned by [owner]: it closes by itself when the owner disables, and every
 * subscription closes (without firing exits) when SneakyUtils disables. Implemented by SneakyUtils only.
 *
 * [close] is idempotent and must be called on the server thread. Closing an area fires an
 * [AreaCause.UNREGISTER] exit for every player inside it; nothing else fires on close.
 */
interface Subscription : AutoCloseable {
    /** Unique for the server's lifetime, for logs and `/triggers`: `<owner>:<kind>#<n>`. */
    val id: String

    /** The plugin this subscription belongs to. */
    val owner: Plugin

    /** False once closed, by [close], owner disable or SneakyUtils disable. */
    val active: Boolean

    /** The diagnostics label it was made with (its options' label, or a [Triggers.labelled] view's), or null. */
    val label: String? get() = null

    override fun close()
}

/**
 * A per-player timer from [Triggers.everyForPlayers]. Each added player sits in one bucket of the interval and is
 * ticked once per interval. Players are removed when they quit. With [PlayerScope.ALL_ONLINE] players are added on
 * join automatically.
 *
 * All methods must be called on the server thread. [add] of a player who is already in the timer, or who is not
 * online, does nothing; [remove] of a player who isn't in it does nothing. Implemented by SneakyUtils only.
 */
interface PlayerTimer : Subscription {
    fun add(p: Player)
    fun remove(p: Player)
    fun contains(p: Player): Boolean

    /** Players currently in the timer. */
    val size: Int
}
