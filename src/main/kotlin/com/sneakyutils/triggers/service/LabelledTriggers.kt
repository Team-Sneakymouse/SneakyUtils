package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.ActionResolver
import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaHandler
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockBreakHandler
import com.sneakyutils.triggers.BlockClickHandler
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.BlockRemovedHandler
import com.sneakyutils.triggers.BlockRemovedOptions
import com.sneakyutils.triggers.BlockUnderHandler
import com.sneakyutils.triggers.BlockWatchHandler
import com.sneakyutils.triggers.BreakOptions
import com.sneakyutils.triggers.ClickOptions
import com.sneakyutils.triggers.EntityClickHandler
import com.sneakyutils.triggers.EntityMatch
import com.sneakyutils.triggers.PlayerScope
import com.sneakyutils.triggers.PlayerTick
import com.sneakyutils.triggers.PlayerTimer
import com.sneakyutils.triggers.PlayerTimerOptions
import com.sneakyutils.triggers.StepHandler
import com.sneakyutils.triggers.Subscription
import com.sneakyutils.triggers.TableLoadResult
import com.sneakyutils.triggers.TimerOptions
import com.sneakyutils.triggers.TriggerStats
import com.sneakyutils.triggers.Triggers
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * [Triggers.labelled]: forwards every call to [service], giving the options [label] when they have none. Options that
 * already carry a label keep it. Options are copied only at subscribe time; handler calls don't pass through here.
 */
internal class LabelledTriggers(private val service: TriggerService, private val label: String) : Triggers {

    // Each DEFAULT gets its labelled copy once, so the common no-options calls allocate nothing extra.
    private val areaDefault = AreaOptions.DEFAULT.toBuilder().label(label).build()
    private val watchDefault = WatchOptions.DEFAULT.toBuilder().label(label).build()
    private val timerDefault = TimerOptions.DEFAULT.toBuilder().label(label).build()
    private val clickDefault = ClickOptions.DEFAULT.toBuilder().label(label).build()
    private val breakDefault = BreakOptions.DEFAULT.toBuilder().label(label).build()
    private val removedDefault = BlockRemovedOptions.DEFAULT.toBuilder().label(label).build()

    private fun l(o: AreaOptions) = if (o.label != null) o else if (o === AreaOptions.DEFAULT) areaDefault else o.toBuilder().label(label).build()
    private fun l(o: WatchOptions) = if (o.label != null) o else if (o === WatchOptions.DEFAULT) watchDefault else o.toBuilder().label(label).build()
    private fun l(o: TimerOptions) = if (o.label != null) o else timerDefault
    private fun l(o: ClickOptions) = if (o.label != null) o else if (o === ClickOptions.DEFAULT) clickDefault else o.toBuilder().label(label).build()
    private fun l(o: BreakOptions) = if (o.label != null) o else if (o === BreakOptions.DEFAULT) breakDefault else o.toBuilder().label(label).build()
    private fun l(o: BlockRemovedOptions) = if (o.label != null) o else removedDefault
    private fun l(o: PlayerTimerOptions) = if (o.label != null) o else o.toBuilder().label(label).build()

    override fun area(owner: Plugin, area: Area, handler: AreaHandler, options: AreaOptions): Subscription =
        service.area(owner, area, handler, l(options))

    override fun watchSteps(owner: Plugin, player: Player, handler: StepHandler, options: WatchOptions): Subscription =
        service.watchSteps(owner, player, handler, l(options))

    override fun watchBlockUnder(
        owner: Plugin,
        player: Player,
        materials: Set<Material>,
        handler: BlockUnderHandler,
        options: WatchOptions,
    ): Subscription = service.watchBlockUnder(owner, player, materials, handler, l(options))

    override fun watchBlock(
        owner: Plugin,
        player: Player,
        at: BlockOffset,
        materials: Set<Material>,
        handler: BlockWatchHandler,
        options: WatchOptions,
    ): Subscription = service.watchBlock(owner, player, at, materials, handler, l(options))

    override fun every(owner: Plugin, intervalTicks: Int, task: Runnable): Subscription =
        service.every(owner, intervalTicks, task, timerDefault)

    override fun every(owner: Plugin, intervalTicks: Int, task: Runnable, options: TimerOptions): Subscription =
        service.every(owner, intervalTicks, task, l(options))

    override fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, scope: PlayerScope): PlayerTimer =
        service.everyForPlayers(owner, intervalTicks, handler, PlayerTimerOptions.builder().scope(scope).label(label).build())

    override fun everyForPlayers(owner: Plugin, intervalTicks: Int, handler: PlayerTick, options: PlayerTimerOptions): PlayerTimer =
        service.everyForPlayers(owner, intervalTicks, handler, l(options))

    override fun onBlockClick(owner: Plugin, block: BlockKey, handler: BlockClickHandler, options: ClickOptions): Subscription =
        service.onBlockClick(owner, block, handler, l(options))

    override fun onBlockType(owner: Plugin, material: Material, handler: BlockClickHandler, options: ClickOptions): Subscription =
        service.onBlockType(owner, material, handler, l(options))

    override fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler): Subscription =
        service.onBlockBreak(owner, block, handler, breakDefault)

    override fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler): Subscription =
        service.onBlockBreakType(owner, material, handler, breakDefault)

    override fun onBlockBreak(owner: Plugin, block: BlockKey, handler: BlockBreakHandler, options: BreakOptions): Subscription =
        service.onBlockBreak(owner, block, handler, l(options))

    override fun onBlockBreakType(owner: Plugin, material: Material, handler: BlockBreakHandler, options: BreakOptions): Subscription =
        service.onBlockBreakType(owner, material, handler, l(options))

    override fun onBlockRemoved(owner: Plugin, block: BlockKey, handler: BlockRemovedHandler): Subscription =
        service.onBlockRemoved(owner, block, handler, removedDefault)

    override fun onBlockRemoved(owner: Plugin, block: BlockKey, handler: BlockRemovedHandler, options: BlockRemovedOptions): Subscription =
        service.onBlockRemoved(owner, block, handler, l(options))

    override fun onEntityClick(owner: Plugin, match: EntityMatch, handler: EntityClickHandler, options: ClickOptions): Subscription =
        service.onEntityClick(owner, match, handler, l(options))

    override fun loadTable(owner: Plugin, file: File, actions: ActionResolver): TableLoadResult =
        service.loadTable(owner, file, actions)

    override fun labelled(label: String): Triggers = service.labelled(label)

    override fun stats(): TriggerStats = service.stats()

    override fun toString(): String = "Triggers(label=$label)"
}
