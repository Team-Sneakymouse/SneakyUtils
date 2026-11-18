package com.sneakyutils.triggers

import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
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
 * Why a watched block is being removed ([Triggers.onBlockRemoved]), and which event reports it. Every event is
 * observed at `MONITOR` and only when nobody cancelled it.
 */
enum class BlockRemoveCause {
    /**
     * A player breaks it ([org.bukkit.event.block.BlockBreakEvent]). Not for breaks Paper refuses after that event: a
     * spectator's, or a game-master block (command, structure, jigsaw, test blocks) the player may not break.
     */
    BREAK,

    /**
     * An explosion destroys it: a block in [org.bukkit.event.block.BlockExplodeEvent.blockList] or
     * [org.bukkit.event.entity.EntityExplodeEvent.blockList] of an explosion that destroys blocks (not a wind charge's
     * [org.bukkit.ExplosionResult.TRIGGER_BLOCK]), and only blocks an explosion can destroy (not bedrock, barriers, end
     * portal frames and the other blocks Paper protects). Also the bed half that was used, or the respawn anchor, that exploded
     * itself, which Paper has already removed when the [org.bukkit.event.block.BlockExplodeEvent] fires (delivered even
     * when that event is cancelled, since the source is gone either way; a bed's other half is not reported). TNT comes
     * from the [org.bukkit.event.block.TNTPrimeEvent] that primes it (cause `EXPLOSION`), so TNT whose priming is
     * cancelled and stays is never reported. An ender dragon's breaks come from its
     * [org.bukkit.event.entity.EntityExplodeEvent] at yield 0 (its default), or from Paper's old TNT prime event per block
     * when a plugin changed the yield.
     */
    EXPLODE,

    /**
     * Fire burns it away ([org.bukkit.event.block.BlockBurnEvent]). TNT comes from Paper's old
     * `com.destroystokyo.paper.event.block.TNTPrimeEvent` with reason `FIRE` instead, the last event that can keep it
     * (the block may already hold fire by then).
     */
    BURN,

    /**
     * It fades, melts or turns into another block by world conditions ([org.bukkit.event.block.BlockFadeEvent]): snow
     * and ice melting, fire burning out, coral dying, grass or farmland turning to dirt.
     */
    FADE,

    /** Leaves decay ([org.bukkit.event.block.LeavesDecayEvent]). */
    DECAY,

    /**
     * A piston moves it away from this position or breaks it (a block in
     * [org.bukkit.event.block.BlockPistonExtendEvent.getBlocks] or
     * [org.bukkit.event.block.BlockPistonRetractEvent.getBlocks]; Paper lists the blocks a piston breaks there too),
     * at the block's original position; or it is the head of a retracting piston (found from the base's facing; a sticky
     * pull's head is reported even when that pull is cancelled, since Paper clears it before the event).
     */
    PISTON,

    /**
     * The server destroys it ([com.destroystokyo.paper.event.block.BlockDestroyEvent]): a block losing its support (a
     * torch, a sign, a carpet), `/setblock ... destroy`, and the breaks of entities that go through the server's
     * destroy (a wither, a ravager, a villager harvesting, a sheep eating short grass, a boat breaking a lily pad, a
     * projectile breaking a chorus flower or pot, trampled turtle eggs or crops). Also, on the next tick, a rail (or a
     * redstone wire, repeater or comparator) whose support another reported event took away: rails always, and the
     * others when their support is broken or blown up, remove themselves with no event, so they are checked after the
     * fact; the block is already gone then, and the event is the support's.
     */
    DESTROY,

    /**
     * An entity changes it directly ([org.bukkit.event.entity.EntityChangeBlockEvent] where the block isn't air and the
     * new type differs): an enderman picking it up, sand or gravel starting to fall, a falling block landing on a
     * replaceable block, a player eating the last slice of cake, a sheep turning grass to dirt, farmland trampled,
     * silverfish infesting stone. Entity breaks that go on to the server's destroy come as [DESTROY], from the event
     * that decides them. A burning projectile priming TNT comes from the [org.bukkit.event.block.TNTPrimeEvent] (cause
     * `PROJECTILE`).
     */
    ENTITY,

    /**
     * Flowing water or lava washes it away ([org.bukkit.event.block.BlockFromToEvent] into a block that isn't air, a
     * fluid or waterloggable); or it is a fluid that recedes or turns into another block
     * ([org.bukkit.event.block.FluidLevelChangeEvent] to a different material, such as flowing water drying up).
     */
    FLUID,

    /**
     * TNT is lit and turns into primed TNT by redstone, a player's flint and steel or a dispenser
     * ([org.bukkit.event.block.TNTPrimeEvent] with cause `REDSTONE`, `PLAYER` or `DISPENSER`).
     */
    PRIME,

    /** A dragon egg teleports away from it ([org.bukkit.event.block.BlockFromToEvent] with face `SELF`). */
    TELEPORT,

    /**
     * Something spreads onto it and turns it into another block ([org.bukkit.event.block.BlockSpreadEvent]): sculk over
     * stone, grass or mycelium over dirt.
     */
    SPREAD,

    /**
     * A player places another block in its position ([org.bukkit.event.block.BlockPlaceEvent] or
     * [org.bukkit.event.block.BlockMultiPlaceEvent], accepted and with `canBuild()`), such as a block placed into short
     * grass. Unlike the other causes, the world already holds the new block when the handler runs; the old one is the
     * event's replaced state.
     */
    REPLACE,

    /**
     * A block forms in its place ([org.bukkit.event.block.BlockFormEvent], including
     * [org.bukkit.event.block.EntityBlockFormEvent]): concrete powder hardening, lava turning to obsidian or
     * cobblestone, water turning to stone or freezing (also frost walker ice). Not copper weathering, and not concrete
     * powder placed straight into water (that is [REPLACE]).
     */
    FORM,

    /**
     * A sponge soaks it up ([org.bukkit.event.block.SpongeAbsorbEvent]): water and water plants. A block that only loses
     * its water (waterlogged) stays and isn't reported.
     */
    ABSORB,

    /**
     * A player scoops it up with a bucket ([org.bukkit.event.player.PlayerBucketFillEvent]): a water or lava source,
     * powder snow or a bubble column. A waterlogged block only loses its water and isn't reported.
     */
    BUCKET,
}

/**
 * A block removal callback ([Triggers.onBlockRemoved]). [block] is the watched position and still holds the old
 * block: the event hasn't been applied yet (except an exploding bed or respawn anchor, already gone, and
 * [BlockRemoveCause.REPLACE], where the world already holds the placed block, and a rail or wire reported as
 * [BlockRemoveCause.DESTROY] on the tick after its support went). [event] is the
 * Bukkit event that reports it (see [BlockRemoveCause]); it is observed at `MONITOR`, so read it but don't cancel or
 * change it.
 */
fun interface BlockRemovedHandler {
    fun onRemoved(block: Block, cause: BlockRemoveCause, event: Event)
}

/**
 * An entity click callback. [event] is a [org.bukkit.event.player.PlayerInteractEntityEvent] for [ClickSide.RIGHT]
 * and a [io.papermc.paper.event.player.PrePlayerAttackEntityEvent] for [ClickSide.LEFT]. Return true to claim the
 * click: with [ClickOptions.cancelEvent] the event is cancelled.
 */
fun interface EntityClickHandler {
    fun onClick(player: Player, entity: Entity, side: ClickSide, event: PlayerEvent): Boolean
}
