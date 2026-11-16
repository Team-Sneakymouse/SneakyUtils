package com.sneakyutils.triggers

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * The shared trigger service: one listener per frequent event for all plugins, which hands each event only to the
 * subscriptions that can apply. Get it with `SneakyUtils.getTriggers()`. Usage, rules and examples are in
 * `docs/triggers.md`.
 *
 * Rules for every method:
 * - Call on the server thread; every method throws [IllegalStateException] on another thread.
 * - [owner] must be enabled ([IllegalArgumentException] otherwise). Its subscriptions close when it disables.
 * - Handlers run on the server thread. A handler that throws is logged (at most once a minute per subscription)
 *   and stays subscribed; other handlers still run.
 * - Subscribing or closing inside a handler is allowed and takes effect from the next event. Enter and exit
 *   callbacks already queued for the current event are still delivered, so enter and exit always pair up.
 *
 * Optional arguments are explicit overloads (no Kotlin default arguments), so Java callers get the same API.
 *
 * Implemented by SneakyUtils only: don't implement this interface (or [Subscription] or [PlayerTimer]) in a plugin.
 * Methods added later come as default methods, but only SneakyUtils' implementation is supported; in tests, load
 * SneakyUtils in MockBukkit, or put the calls you need behind your own small interface and fake that.
 */
interface Triggers {

    // ---- areas

    /** Calls [handler] when players enter or leave [area] (and on steps inside, with [AreaOptions.stepEvents]). */
    fun area(owner: Plugin, area: Area, handler: AreaHandler, options: AreaOptions): Subscription

    fun area(owner: Plugin, area: Area, handler: AreaHandler): Subscription =
        area(owner, area, handler, AreaOptions.DEFAULT)

    // ---- per-player watches

    /**
     * Calls [handler] on each block step of [player] until closed. Ends on quit unless [WatchOptions.keepOnQuit],
     * and on world change unless [WatchOptions.keepOnWorldChange]. A watch for a player who is offline and
     * without keepOnQuit is returned already closed.
     */
    fun watchSteps(owner: Plugin, player: Player, handler: StepHandler, options: WatchOptions): Subscription

    fun watchSteps(owner: Plugin, player: Player, handler: StepHandler): Subscription =
        watchSteps(owner, player, handler, WatchOptions.DEFAULT)

    /**
     * Calls [handler] when [player]'s block position changes and the block under their feet is one of [materials]
     * (every such step, not only the first). The block is read only for watched players and only when their block
     * position changes. The block under the feet is [BlockOffset.BELOW]. Same as [watchBlock] with
     * [BlockOffset.BELOW], without the [BlockMatch.LEFT] call.
     */
    fun watchBlockUnder(
        owner: Plugin,
        player: Player,
        materials: Set<Material>,
        handler: BlockUnderHandler,
        options: WatchOptions,
    ): Subscription

    fun watchBlockUnder(owner: Plugin, player: Player, materials: Set<Material>, handler: BlockUnderHandler): Subscription =
        watchBlockUnder(owner, player, materials, handler, WatchOptions.DEFAULT)

    /**
     * Watches the block at [at] (the feet block or the block under the feet) on [player]'s block steps: calls
     * [handler] with [BlockMatch.ENTERED] or [BlockMatch.STAYED] when it is one of [materials], and once with
     * [BlockMatch.LEFT] when it stops being one. Like [watchBlockUnder], the block is read only for watched players
     * and only when their block position changes. Steps the options skip (riding, teleports) don't update the
     * previous match, so the next counted step compares with the last counted one.
     */
    fun watchBlock(
        owner: Plugin,
        player: Player,
        at: BlockOffset,
        materials: Set<Material>,
        handler: BlockWatchHandler,
        options: WatchOptions,
    ): Subscription

    fun watchBlock(owner: Plugin, player: Player, at: BlockOffset, materials: Set<Material>, handler: BlockWatchHandler): Subscription =
        watchBlock(owner, player, at, materials, handler, WatchOptions.DEFAULT)

    // ---- timers

    /**
     * Runs [task] every [intervalTicks] ticks (1..72000), staggered against other timers with the same interval.
     * The first run is within one interval. No catch-up: a lagging server never runs it twice in one tick.
     */
    fun every(owner: Plugin, intervalTicks: Int, task: Runnable): Subscription

    /**
     * Calls [handler] for each player in the timer every [intervalTicks] ticks (1..72000). Players are spread over
     * the interval's buckets, so N players cost about N / interval calls per tick. Dead players are paused until
     * they respawn (see [PlayerTimerOptions.skipDead]).
     */
    fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, scope: PlayerScope): PlayerTimer

    fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick): PlayerTimer =
        everyForPlayers(owner, intervalTicks, handler, PlayerScope.ADDED)

    /**
     * [everyForPlayers] with options; [PlayerTimerOptions.skipDead] off keeps calling dead players. (A default
     * method, for implementations from before the options; only SneakyUtils' implements every option.)
     */
    fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, options: PlayerTimerOptions): PlayerTimer {
        if (!options.skipDead) throw UnsupportedOperationException("this Triggers implementation can't keep calling dead players")
        return everyForPlayers(owner, intervalTicks, handler, options.scope)
    }

    // ---- blocks

    /** Clicks on one block. [block] must be packable ([BlockKey.isPackable]). */
    fun onBlockClick(owner: Plugin, block: BlockKey, handler: BlockClickHandler, options: ClickOptions): Subscription

    fun onBlockClick(owner: Plugin, block: BlockKey, handler: BlockClickHandler): Subscription =
        onBlockClick(owner, block, handler, ClickOptions.DEFAULT)

    /** Clicks on any block of [material] (a block material). */
    fun onBlockType(owner: Plugin, material: Material, handler: BlockClickHandler, options: ClickOptions): Subscription

    fun onBlockType(owner: Plugin, material: Material, handler: BlockClickHandler): Subscription =
        onBlockType(owner, material, handler, ClickOptions.DEFAULT)

    /** A player breaking one block that nobody cancelled. [block] must be packable ([BlockKey.isPackable]). */
    fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler): Subscription

    /** A player breaking any block of [material] that nobody cancelled. */
    fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler): Subscription

    /**
     * [onBlockBreak] with options: [BreakOptions.ignoreCancelled] off also delivers cancelled breaks. (A default
     * method, for implementations from before the options; only SneakyUtils' implements every option.)
     */
    fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler, options: BreakOptions): Subscription {
        if (!options.ignoreCancelled) throw UnsupportedOperationException("this Triggers implementation can't deliver cancelled breaks")
        return onBlockBreak(owner, block, handler)
    }

    /** [onBlockBreakType] with options (see the [onBlockBreak] overload with [BreakOptions]). A default method. */
    fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler, options: BreakOptions): Subscription {
        if (!options.ignoreCancelled) throw UnsupportedOperationException("this Triggers implementation can't deliver cancelled breaks")
        return onBlockBreakType(owner, material, handler)
    }

    // ---- entities

    /** Clicks on entities that match [match]. */
    fun onEntityClick(owner: Plugin, match: EntityMatch, handler: EntityClickHandler, options: ClickOptions): Subscription

    fun onEntityClick(owner: Plugin, match: EntityMatch, handler: EntityClickHandler): Subscription =
        onEntityClick(owner, match, handler, ClickOptions.DEFAULT)

    // ---- data tables

    /**
     * Loads block clicks, block breaks and areas from a YAML table and resolves each entry's action with [actions].
     * Loading the same file again for the same owner closes the previous load's subscriptions first (unless the
     * file can't be read or parsed at all). Reads the file on the server thread: call it on enable or reload.
     */
    fun loadTable(owner: Plugin, file: File, actions: ActionResolver): TableLoadResult

    // ---- introspection

    fun stats(): TriggerStats
}
