package com.sneakyutils.triggers

import org.bukkit.entity.Player

/**
 * Who is inside an area, kept by the trigger service: from [Triggers.occupancy]. Usage, defaults and costs are in
 * `docs/triggers.md` (Occupancy).
 *
 * The handle follows the calls delivered to its [OccupancyHandler]: a player is in [players] from their
 * [OccupancyHandler.onEnter] until their [OccupancyHandler.onExit], so [count] goes 0 -> 1 exactly when
 * [OccupancyHandler.onFirstIn] runs and 1 -> 0 exactly when [OccupancyHandler.onLastOut] runs. Inside a handler,
 * changes another handler set off (a teleport, [moveTo], [close]) show from their own calls on, which come after the
 * current one.
 *
 * An occupancy is a [Subscription]: owned by the plugin that made it, closed silently when that plugin disables (no
 * calls reach a disabling plugin) or when the area's world unloads, and listed in `/triggers` and diagnostics under the
 * kind `occupancy`. [close] is idempotent and, like closing an area, takes every player out with
 * [OccupancyCause.UNREGISTER] (exits, then the last-out). Implemented by SneakyUtils only: don't implement this
 * interface; in tests, fake your own seam.
 *
 * Server thread only, like every trigger call.
 */
interface Occupancy : Subscription {
    /** The current area: the one registered, or the last one passed to [moveTo]. */
    val area: Area

    /** How many players are inside now (see the class comment for "now"). */
    val count: Int

    /** Whether anyone is inside: `count > 0`. */
    val isOccupied: Boolean

    /**
     * The players inside, in the order they entered, as a snapshot: a new unmodifiable list on every call (it
     * allocates), unaffected by later changes, so it is safe to iterate while moving players or the area. Empty once
     * closed and emptied. Don't keep it past the current tick: it holds [Player] objects.
     */
    val players: List<Player>

    /** Whether [player] is inside now. No allocation. */
    fun contains(player: Player): Boolean

    /**
     * Re-anchors the occupancy on [area] (another position, shape or world) at once, for moving stations and
     * vehicles. Players inside both the old and the new area stay inside without calls; players only in the new area
     * enter, players only in the old one exit, both with [OccupancyCause.AREA_MOVED]. Enters are called before exits,
     * so [OccupancyHandler.onFirstIn] and [OccupancyHandler.onLastOut] run only when the count really goes from or to 0.
     * The usual rules decide who may enter (riders, spectators; dead players don't enter, but a dead member stays while
     * the new area still holds their position). Players are tested where they are now: for a shaped area at their
     * current exact position, or, for a player whose own move, teleport or respawn is being handled, at its
     * destination.
     *
     * Passing the current [area] again re-evaluates who is inside (players tested where they are now) without
     * touching the index.
     *
     * Cost: one box test per online player (plus the shape test for those in the box), and index updates only for
     * the chunks the area starts or stops touching (none for a move within the same chunks). Calling it every tick for
     * a moving station is fine; calling it with an unchanged position is cheap but not free. Moving to another world
     * is supported: everyone in the old world exits.
     *
     * Throws [IllegalStateException] when closed, off the server thread or inside an area predicate. Inside a handler
     * it takes effect at once, and its calls come after the current one.
     */
    fun moveTo(area: Area)

    /** [moveTo] the current area moved by whole blocks ([Area.translate]; throws if that leaves the int range). */
    fun translate(dx: Int, dy: Int, dz: Int) = moveTo(area.translate(dx, dy, dz))
}

/**
 * Why a player entered or left an [Occupancy]. The same as [AreaCause] for the shared ones, plus [GAME_MODE] and
 * [AREA_MOVED].
 */
enum class OccupancyCause {
    /**
     * A step on foot (including a dismount that lets the player in). Also the exit of a member who walked out of a
     * shape within their block and was found outside when they mounted.
     */
    MOVE,

    /**
     * A step while riding, or a mount that lets the player in ([RiderPolicy.ONLY]). Also the exit of a member who rode
     * out of a shape within their block and was found outside when they dismounted.
     */
    VEHICLE,

    /**
     * A teleport, a single move longer than 64 blocks, or a respawn: a dead member respawning outside the area leaves
     * with this cause, one respawning inside stays.
     */
    TELEPORT,

    /** The player joined inside the area. */
    JOIN,

    /** The player changed world. */
    WORLD_CHANGE,

    /** The occupancy was made with the player already inside. */
    REGISTER,

    /** The player quit (or was kicked), dead or alive. */
    QUIT,

    /** The occupancy was closed with [Subscription.close] (or its world unloaded). Not on owner or SneakyUtils disable. */
    UNREGISTER,

    /** The player died inside, with [OccupancyOptions.countDead] off. With the default, death doesn't exit. */
    DEATH,

    /**
     * The player's game mode changed: a counted member switching to spectator leaves (unless
     * [OccupancyOptions.includeSpectators]), switching back while inside enters.
     */
    GAME_MODE,

    /** [Occupancy.moveTo] moved the area onto or off the player. */
    AREA_MOVED,
}

/**
 * Callbacks of an [Occupancy]. Every method has an empty default: implement only what you need, or use [of].
 * Yours to implement; anything added later is a default method.
 *
 * Order: when a player makes the count go 0 -> 1 they get [onFirstIn], then [onEnter]; when they make it go 1 -> 0,
 * [onExit], then [onLastOut]. For one player the calls alternate enter, exit.
 */
interface OccupancyHandler {
    /** The count went from 0 to 1: [player] is the first one in. Called just before their [onEnter]. */
    fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {}

    /** The count went from 1 to 0: [player] was the last one out. Called just after their [onExit]. */
    fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {}

    /** [player] entered (every player, the first one included). */
    fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {}

    /** [player] left (every player, the last one included). */
    fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {}

    companion object {
        /** A handler with only first-in and last-out callbacks; either may be null. */
        @JvmStatic
        fun of(firstIn: OccupancyListener?, lastOut: OccupancyListener?): OccupancyHandler = object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                firstIn?.handle(occupancy, player, cause)
            }

            override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                lastOut?.handle(occupancy, player, cause)
            }
        }

        /** A handler with all four callbacks; any may be null. */
        @JvmStatic
        fun of(
            firstIn: OccupancyListener?,
            lastOut: OccupancyListener?,
            enter: OccupancyListener?,
            exit: OccupancyListener?,
        ): OccupancyHandler = object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                firstIn?.handle(occupancy, player, cause)
            }

            override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                lastOut?.handle(occupancy, player, cause)
            }

            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                enter?.handle(occupancy, player, cause)
            }

            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                exit?.handle(occupancy, player, cause)
            }
        }
    }
}

/** One occupancy callback, for [OccupancyHandler.of]. */
fun interface OccupancyListener {
    fun handle(occupancy: Occupancy, player: Player, cause: OccupancyCause)
}

/**
 * Options for [Triggers.occupancy]. Immutable; build with [builder] or change a copy with [toBuilder].
 *
 * Who counts, by default: players on foot and riders; not spectators; dead players stay counted until they respawn
 * elsewhere or leave by other means (quit, world change, the area moving off them).
 */
class OccupancyOptions private constructor(b: Builder) {
    /**
     * Which players enter, by riding state. Default [RiderPolicy.ALLOW] (riders count). As for areas, it decides
     * entering only: a member who mounts or dismounts inside stays.
     */
    val riders: RiderPolicy = b.riders

    /**
     * Whether spectators count. Default false. Unlike areas (where it decides entering only), it holds all the time:
     * a member who switches to spectator leaves ([OccupancyCause.GAME_MODE]) and enters again when they switch back
     * while still inside.
     */
    val includeSpectators: Boolean = b.includeSpectators

    /**
     * Whether a member who dies stays counted. Default true: they stay inside until they respawn outside the area
     * ([OccupancyCause.TELEPORT]), quit, change world, or the area moves off them; respawning inside keeps them in
     * without calls. With false they leave on death ([OccupancyCause.DEATH]), as in areas. Either way a player who is
     * already dead when the occupancy is made, or who joins dead, enters only when they respawn.
     */
    val countDead: Boolean = b.countDead

    /** Diagnostics label (see [TriggerLabels]); null: none. Default null. */
    val label: String? = b.label

    fun toBuilder(): Builder = Builder().riders(riders).includeSpectators(includeSpectators).countDead(countDead).label(label)

    override fun toString(): String =
        "OccupancyOptions(riders=$riders, includeSpectators=$includeSpectators, countDead=$countDead, label=$label)"

    class Builder internal constructor() {
        internal var riders = RiderPolicy.ALLOW
        internal var includeSpectators = false
        internal var countDead = true
        internal var label: String? = null

        fun riders(value: RiderPolicy) = apply { riders = value }
        fun includeSpectators(value: Boolean) = apply { includeSpectators = value }
        fun countDead(value: Boolean) = apply { countDead = value }
        /** Diagnostics label; throws [IllegalArgumentException] unless null or valid ([TriggerLabels.check]). */
        fun label(value: String?) = apply { label = TriggerLabels.check(value) }
        fun build(): OccupancyOptions = OccupancyOptions(this)
    }

    companion object {
        @JvmField
        val DEFAULT: OccupancyOptions = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
