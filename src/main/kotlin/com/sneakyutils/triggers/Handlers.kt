package com.sneakyutils.triggers

import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Why a player entered or left an area.
 *
 * Enter causes: [MOVE], [VEHICLE], [TELEPORT], [JOIN], [WORLD_CHANGE], [REGISTER].
 * Exit causes: [MOVE], [VEHICLE], [TELEPORT], [QUIT], [WORLD_CHANGE], [UNREGISTER], [DEATH].
 */
enum class AreaCause {
    /** A step on foot (including a dismount that leaves the player inside). */
    MOVE,

    /** A step while riding (the move event or the mounted poll), or mounting inside a [RiderPolicy.ONLY] area. */
    VEHICLE,

    /** A teleport, a respawn, or a single move longer than the segment limit (64 blocks). */
    TELEPORT,

    /** The player joined inside the area ([AreaOptions.fireOnJoin]). */
    JOIN,

    /** The player changed world. Exits fire for the old world's areas, enters for the new world's. */
    WORLD_CHANGE,

    /** The area was registered with the player already inside ([AreaOptions.fireOnRegister]). */
    REGISTER,

    /** The player quit (or was kicked). */
    QUIT,

    /** The area's subscription was closed with [Subscription.close]. Not fired on owner or SneakyUtils disable. */
    UNREGISTER,

    /** The player died inside the area. */
    DEATH,
}

/** Which players an area accepts, by whether they ride something. */
enum class RiderPolicy {
    /** Riders don't enter (default). A player who was inside before mounting stays inside until they leave it. */
    SKIP,

    /** Riders and players on foot both enter. */
    ALLOW,

    /** Only riders enter. A rider who dismounts inside stays inside until they leave it. */
    ONLY,
}

/** Who is in a per-player timer. */
enum class PlayerScope {
    /** Only players added with [PlayerTimer.add] (default). */
    ADDED,

    /** Every online player, tracked on join and quit. Allowed, but counted and flagged in [TriggerStats]. */
    ALL_ONLINE,
}

/** Which click a block or entity subscription accepts, and which click happened. */
enum class ClickSide {
    RIGHT,
    LEFT,

    /** Accepts [RIGHT] and [LEFT]. Never passed to a handler. */
    BOTH,

    /** Stepping on a pressure plate, tripwire or farmland ([org.bukkit.event.block.Action.PHYSICAL]). */
    PHYSICAL;

    /** Whether an option set to this side accepts a click on [actual]. */
    fun accepts(actual: ClickSide): Boolean = this == actual || (this == BOTH && (actual == RIGHT || actual == LEFT))
}

/**
 * Enter, exit and (with [AreaOptions.stepEvents]) step callbacks for an area. Every method has an empty default,
 * so implement only what you need, or use [onEnter] / [of].
 *
 * For one player and one area the calls always alternate: enter, (steps), exit. A player counted inside silently
 * (see [AreaOptions.fireOnRegister] and [AreaOptions.fireOnJoin]) gets neither enter nor exit for that stay.
 *
 * Handler interfaces are yours to implement. Anything added to them later is a default method (like the
 * three-argument [onStep]), so existing implementations keep compiling and behaving the same.
 */
interface AreaHandler {
    fun onEnter(player: Player, area: Area, cause: AreaCause) {}
    fun onExit(player: Player, area: Area, cause: AreaCause) {}

    /**
     * Called for each block step inside the area, only when [AreaOptions.stepEvents] is set. Teleports within the
     * area count as steps too; override the three-argument [onStep] to tell them apart.
     */
    fun onStep(player: Player, area: Area) {}

    /**
     * Called for each block step inside the area, only when [AreaOptions.stepEvents] is set, with how the player got
     * to the new block: [AreaCause.MOVE] on foot, [AreaCause.VEHICLE] riding, [AreaCause.TELEPORT] for a teleport, a
     * respawn or a move longer than the segment limit. The default calls the two-argument [onStep].
     */
    fun onStep(player: Player, area: Area, cause: AreaCause) = onStep(player, area)

    companion object {
        /** A handler with only an enter callback. */
        @JvmStatic
        fun onEnter(enter: AreaListener): AreaHandler = of(enter, null)

        /** A handler with enter and exit callbacks; either may be null. */
        @JvmStatic
        fun of(enter: AreaListener?, exit: AreaListener?): AreaHandler = object : AreaHandler {
            override fun onEnter(player: Player, area: Area, cause: AreaCause) {
                enter?.handle(player, area, cause)
            }

            override fun onExit(player: Player, area: Area, cause: AreaCause) {
                exit?.handle(player, area, cause)
            }
        }
    }
}

/** One area callback, for [AreaHandler.of]. */
fun interface AreaListener {
    fun handle(player: Player, area: Area, cause: AreaCause)
}

/** A per-player step watch: called when the watched player's block position changes. */
fun interface StepHandler {
    /** [from] and [to] are in the same world unless the step was a world change (only with includeTeleports). */
    fun onStep(player: Player, from: BlockKey, to: BlockKey, mounted: Boolean)
}

/** A block-under watch: called when the block under the watched player changes to one of the watched materials. */
fun interface BlockUnderHandler {
    fun onBlockUnder(player: Player, block: Block)
}

/** Which block a [Triggers.watchBlock] reads, relative to the player's feet. */
enum class BlockOffset {
    /** The block the feet are in (`floor` of the feet position): water, soul sand, cobweb, a carpet. */
    FEET,

    /**
     * The block under the feet: the block just below the feet's height and, when that is air, the one below it
     * (so fence and wall tops count, as do slabs and carpets).
     */
    BELOW,
}

/** How a [Triggers.watchBlock] step relates to the watched materials. */
enum class BlockMatch {
    /** The block matches; at the previous step it didn't (or this is the watch's first step). */
    ENTERED,

    /** The block matches, and it matched at the previous step too. */
    STAYED,

    /** The block doesn't match; at the previous step it did. [BlockWatchHandler.onBlock] gets the new block. */
    LEFT,
}

/** A block watch: called on a block step when the watched block matches, and once when it stops matching. */
fun interface BlockWatchHandler {
    fun onBlock(player: Player, block: Block, match: BlockMatch)
}

/** A per-player timer callback. */
fun interface PlayerTick {
    fun tick(player: Player)
}

/** A block click callback. Return true to claim the click: with [ClickOptions.cancelEvent] the event is cancelled. */
fun interface BlockClickHandler {
    fun onClick(player: Player, block: Block, side: ClickSide, event: PlayerInteractEvent): Boolean
}

/** A block break callback. Cancel through [event] if needed. */
fun interface BlockBreakHandler {
    fun onBreak(player: Player, block: Block, event: BlockBreakEvent)
}

/**
 * An entity click callback. [event] is a [org.bukkit.event.player.PlayerInteractEntityEvent] for [ClickSide.RIGHT]
 * and a [io.papermc.paper.event.player.PrePlayerAttackEntityEvent] for [ClickSide.LEFT]. Return true to claim the
 * click: with [ClickOptions.cancelEvent] the event is cancelled.
 */
fun interface EntityClickHandler {
    fun onClick(player: Player, entity: Entity, side: ClickSide, event: PlayerEvent): Boolean
}
