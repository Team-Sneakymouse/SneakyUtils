package com.sneakyutils.triggers

import org.bukkit.inventory.EquipmentSlot
import java.util.EnumSet

/**
 * Options for [Triggers.area]. Immutable; build with [builder] or change a copy with [toBuilder].
 *
 * Membership rule (riders and spectators): [riders] and [includeSpectators] only decide whether a player may
 * **enter**. A player who is inside stays inside until their position leaves the area (or they quit, die, change
 * world, or the area closes), whatever they ride or their game mode. So a player who mounts inside a
 * [RiderPolicy.SKIP] area stays inside until they drive out, and a rider who drove into a SKIP area enters it when
 * they dismount there.
 */
class AreaOptions private constructor(b: Builder) {
    /** Which players enter, by riding state. Default [RiderPolicy.SKIP]. */
    val riders: RiderPolicy = b.riders

    /** Whether spectators enter. Default false. */
    val includeSpectators: Boolean = b.includeSpectators

    /**
     * When true, a single move (on foot or riding, not a teleport) whose path crosses the area but starts and ends
     * outside it fires enter then exit (race checkpoints). Default false.
     */
    val passThrough: Boolean = b.passThrough

    /** Whether a player who joins inside the area gets an enter ([AreaCause.JOIN]). Default true. */
    val fireOnJoin: Boolean = b.fireOnJoin

    /** Whether players already inside when the area registers get an enter ([AreaCause.REGISTER]). Default false. */
    val fireOnRegister: Boolean = b.fireOnRegister

    /** Whether [AreaHandler.onStep] is called for block steps inside the area. Default false. */
    val stepEvents: Boolean = b.stepEvents

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder()
        .riders(riders).includeSpectators(includeSpectators).passThrough(passThrough)
        .fireOnJoin(fireOnJoin).fireOnRegister(fireOnRegister).stepEvents(stepEvents).label(label)

    override fun toString(): String =
        "AreaOptions(riders=$riders, includeSpectators=$includeSpectators, passThrough=$passThrough, " +
            "fireOnJoin=$fireOnJoin, fireOnRegister=$fireOnRegister, stepEvents=$stepEvents, label=$label)"

    class Builder internal constructor() {
        internal var riders = RiderPolicy.SKIP
        internal var includeSpectators = false
        internal var passThrough = false
        internal var fireOnJoin = true
        internal var fireOnRegister = false
        internal var stepEvents = false
        internal var label: String? = null

        fun riders(value: RiderPolicy) = apply { riders = value }
        fun includeSpectators(value: Boolean) = apply { includeSpectators = value }
        fun passThrough(value: Boolean) = apply { passThrough = value }
        fun fireOnJoin(value: Boolean) = apply { fireOnJoin = value }
        fun fireOnRegister(value: Boolean) = apply { fireOnRegister = value }
        fun stepEvents(value: Boolean) = apply { stepEvents = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): AreaOptions = AreaOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: AreaOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Options for [Triggers.watchSteps] and [Triggers.watchBlockUnder]. Immutable; build with [builder].
 */
class WatchOptions private constructor(b: Builder) {
    /** Skip the watch while the player rides something. Default true. */
    val onFootOnly: Boolean = b.onFootOnly

    /**
     * Keep the watch when the player quits; it resumes when they join again. Default false (the watch closes on
     * quit). A kept watch still closes on owner disable and SneakyUtils disable.
     */
    val keepOnQuit: Boolean = b.keepOnQuit

    /** Keep the watch when the player changes world. Default true. */
    val keepOnWorldChange: Boolean = b.keepOnWorldChange

    /** Also call the watch for teleports, respawns and world changes. Default false. */
    val includeTeleports: Boolean = b.includeTeleports

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder()
        .onFootOnly(onFootOnly).keepOnQuit(keepOnQuit).keepOnWorldChange(keepOnWorldChange)
        .includeTeleports(includeTeleports).label(label)

    override fun toString(): String =
        "WatchOptions(onFootOnly=$onFootOnly, keepOnQuit=$keepOnQuit, keepOnWorldChange=$keepOnWorldChange, " +
            "includeTeleports=$includeTeleports, label=$label)"

    class Builder internal constructor() {
        internal var onFootOnly = true
        internal var keepOnQuit = false
        internal var keepOnWorldChange = true
        internal var includeTeleports = false
        internal var label: String? = null

        fun onFootOnly(value: Boolean) = apply { onFootOnly = value }
        fun keepOnQuit(value: Boolean) = apply { keepOnQuit = value }
        fun keepOnWorldChange(value: Boolean) = apply { keepOnWorldChange = value }
        fun includeTeleports(value: Boolean) = apply { includeTeleports = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): WatchOptions = WatchOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: WatchOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Options for [Triggers.everyForPlayers]. Immutable; build with [builder].
 */
class PlayerTimerOptions private constructor(b: Builder) {
    /** Who is in the timer. Default [PlayerScope.ADDED]. */
    val scope: PlayerScope = b.scope

    /**
     * Pause dead players: from death to respawn the player stays in the timer ([PlayerTimer.contains] and
     * [PlayerTimer.size] count them) but isn't called. Default true, as MagicSpells' `ticks` passives did. Costs
     * nothing per tick: the player leaves the timer's buckets on death and goes back on respawn.
     */
    val skipDead: Boolean = b.skipDead

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder().scope(scope).skipDead(skipDead).label(label)

    override fun toString(): String = "PlayerTimerOptions(scope=$scope, skipDead=$skipDead, label=$label)"

    class Builder internal constructor() {
        internal var scope = PlayerScope.ADDED
        internal var skipDead = true
        internal var label: String? = null

        fun scope(value: PlayerScope) = apply { scope = value }
        fun skipDead(value: Boolean) = apply { skipDead = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): PlayerTimerOptions = PlayerTimerOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: PlayerTimerOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Options for block breaks ([Triggers.onBlockBreak], [Triggers.onBlockBreakType]). Immutable; build with [builder].
 */
class BreakOptions private constructor(b: Builder) {
    /**
     * Skip breaks that are already cancelled (by a protection plugin, or by an earlier break handler). Default true.
     * With false the handler also sees cancelled breaks; check `event.isCancelled`, and un-cancel only on purpose.
     */
    val ignoreCancelled: Boolean = b.ignoreCancelled

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder().ignoreCancelled(ignoreCancelled).label(label)

    override fun toString(): String = "BreakOptions(ignoreCancelled=$ignoreCancelled, label=$label)"

    class Builder internal constructor() {
        internal var ignoreCancelled = true
        internal var label: String? = null

        fun ignoreCancelled(value: Boolean) = apply { ignoreCancelled = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): BreakOptions = BreakOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: BreakOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Options for block and entity clicks. Immutable; build with [builder].
 */
class ClickOptions private constructor(b: Builder) {
    /**
     * Hands whose click counts. Paper fires `PlayerInteractEvent` once per hand, so the default is the main hand
     * only ([EquipmentSlot.HAND]). Physical clicks have no hand and ignore this.
     */
    val hands: Set<EquipmentSlot> = java.util.Collections.unmodifiableSet(EnumSet.copyOf(b.hands))

    /** Which click counts. Default [ClickSide.RIGHT]. */
    val sides: ClickSide = b.sides

    /** Skip clicks another plugin already cancelled. Default true. */
    val ignoreCancelled: Boolean = b.ignoreCancelled

    /** Cancel the click when the handler returns true. Default false. */
    val cancelEvent: Boolean = b.cancelEvent

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    internal val mainHand: Boolean = EquipmentSlot.HAND in hands
    internal val offHand: Boolean = EquipmentSlot.OFF_HAND in hands

    fun toBuilder(): Builder =
        Builder().hands(hands).sides(sides).ignoreCancelled(ignoreCancelled).cancelEvent(cancelEvent).label(label)

    override fun toString(): String =
        "ClickOptions(hands=$hands, sides=$sides, ignoreCancelled=$ignoreCancelled, cancelEvent=$cancelEvent, label=$label)"

    class Builder internal constructor() {
        internal var hands: Set<EquipmentSlot> = EnumSet.of(EquipmentSlot.HAND)
        internal var sides = ClickSide.RIGHT
        internal var ignoreCancelled = true
        internal var cancelEvent = false
        internal var label: String? = null

        /** Must be non-empty and contain only [EquipmentSlot.HAND] and [EquipmentSlot.OFF_HAND]. */
        fun hands(value: Set<EquipmentSlot>) = apply {
            require(value.isNotEmpty()) { "hands must not be empty" }
            require(value.all { it == EquipmentSlot.HAND || it == EquipmentSlot.OFF_HAND }) {
                "hands may only contain HAND and OFF_HAND, got $value"
            }
            hands = EnumSet.copyOf(value)
        }

        fun sides(value: ClickSide) = apply { sides = value }
        fun ignoreCancelled(value: Boolean) = apply { ignoreCancelled = value }
        fun cancelEvent(value: Boolean) = apply { cancelEvent = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): ClickOptions = ClickOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: ClickOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Options for [Triggers.every] (global timers). Immutable; build with [builder].
 */
class TimerOptions private constructor(b: Builder) {
    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder().label(label)

    override fun toString(): String = "TimerOptions(label=$label)"

    class Builder internal constructor() {
        internal var label: String? = null

        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): TimerOptions = TimerOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: TimerOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * Diagnostics labels: an optional, stable name a subscription carries so SneakyDiagnostics captures and `/triggers top`
 * tell its cost apart from other subscriptions of the same plugin and kind (see `docs/triggers.md`, Diagnostics).
 *
 * A label is a fixed name chosen by the subscribing plugin, the same on every start and shared by every subscription
 * that does the same job (one rule's areas, one feature's watches). Never a player name, UUID, location, counter or
 * other text that varies. 1 to [MAX_LENGTH] characters of `a-z 0-9 . _ : -`, starting with a letter or digit, for
 * example `holdover:forge-bellow`. Prefix it with your plugin or feature so it reads on its own.
 */
object TriggerLabels {
    const val MAX_LENGTH = 64

    /** Whether [label] is a valid label. */
    @JvmStatic
    fun isValid(label: String): Boolean {
        if (label.isEmpty() || label.length > MAX_LENGTH) return false
        for (i in label.indices) {
            val c = label[i]
            val ok = c in 'a'..'z' || c in '0'..'9' || (i > 0 && (c == '.' || c == '_' || c == ':' || c == '-'))
            if (!ok) return false
        }
        return true
    }

    /** Returns [label] when it is null or valid; otherwise throws [IllegalArgumentException]. */
    @JvmStatic
    fun check(label: String?): String? {
        if (label != null) {
            require(isValid(label)) {
                "trigger label '$label' must be 1..$MAX_LENGTH characters of a-z 0-9 . _ : - starting with a letter or digit"
            }
        }
        return label
    }
}
