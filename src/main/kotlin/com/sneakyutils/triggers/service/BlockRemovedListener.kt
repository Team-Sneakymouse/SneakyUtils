package com.sneakyutils.triggers.service

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import com.sneakyutils.triggers.BlockRemoveCause
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.PistonHead
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Enderman
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.SpongeAbsorbEvent
import org.bukkit.event.block.FluidLevelChangeEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketFillEvent

/**
 * The events behind [com.sneakyutils.triggers.Triggers.onBlockRemoved]. At MONITOR and ignoreCancelled (except the
 * exploding bed or anchor, see [onBlockExplode]): the removal is certain, and the block is still in place. Each handler
 * returns at once while nothing is subscribed; otherwise an event costs one index lookup per block it touches, and the
 * filters that read block types or data run only for a watched block. Registered on the first removal subscription
 * ([TriggerService.ensureRemovalListener]).
 *
 * Each removal is reported by the event that decides it, as read in Paper 26.2's server code:
 * - Entity breaks that go on to `Level.destroyBlock` (wither, ravager, villager harvest, sheep on short grass, rabbit,
 *   silverfish, potions on fire, boats on lily pads, projectiles on chorus, pots and dripstone, crops, powder snow)
 *   fire a cancellable `BlockDestroyEvent` after their `EntityChangeBlockEvent`: they are DESTROY, not ENTITY.
 *   `EntityChangeBlockEvent` to air (or a fluid) is delivered only for the paths that apply it themselves: endermen,
 *   falling blocks and cake.
 * - TNT can be listed by an explosion, burnt or hit by a burning projectile and still stay when its `TNTPrimeEvent` is
 *   cancelled; it is reported from that event, or for fire from Paper's old `TNTPrimeEvent`, which comes last.
 * - An ender dragon's EntityExplodeEvent at yield 0 is final (Paper removes the listed blocks directly); at any other
 *   yield Paper's old `TNTPrimeEvent` decides each block.
 * - A BlockBreakEvent can still be refused for spectators and game-master blocks ([refused]).
 *
 * Not used: `BlockPhysicsEvent` (far too frequent; a block that loses its support is reported by `BlockDestroyEvent`),
 * and Paper's `BlockBreakBlockEvent` (not cancellable and fired while drops are made; the blocks it reports, broken by
 * pistons and fluids, already come from the piston events and `BlockFromToEvent`).
 */
internal class BlockRemovedListener(private val service: TriggerService) : Listener {
    private val removals = service.removals

    /**
     * Whether [OldTntPrimeListener] is registered (Paper still has its old TNTPrimeEvent). It decides the TNT that
     * fire primes and every block of an ender dragon's non-zero-yield destruction; without it those are reported from
     * the events before it.
     */
    @JvmField var oldPrime = false

    private fun one(block: Block, cause: BlockRemoveCause, event: Event) {
        val subs = removals.lookup(block) ?: return
        removals.deliver(block, subs, cause, event)
    }

    /** [block] goes: its watched neighbours that remove themselves without an event are checked, then [block]. */
    private fun gone(block: Block, cause: BlockRemoveCause, event: Event) {
        removals.checkNeighbours(block, event)
        one(block, cause, event)
    }

    /**
     * Paper refuses some breaks after this event: a game-master block (command, structure, jigsaw, test blocks) broken
     * by a player who can't use them, and any break by a spectator. Those are skipped.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        if (!removals.watches(block.world) || refused(event.player, block.type)) return
        gone(block, BlockRemoveCause.BREAK, event)
    }

    /**
     * Not ignoreCancelled: a bed or respawn anchor that explodes itself is removed before this event, so its position
     * is delivered whether or not the event is cancelled. The blast's own blocks only when it isn't. [Block] is the
     * source position (air by now) and [BlockExplodeEvent.getExplodedBlockState] its snapshot. A bed's other half is
     * not reported: Paper removes it only when it is the matching half, and nothing left shows whether it was.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (removals.subs == 0) return
        removals.events++
        val source = event.block
        var first: Block? = null
        val exploded = event.explodedBlockState.type
        if (exploded != source.type && (exploded == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(exploded))) first = source
        val blasts = !event.isCancelled && destroys(event.explosionResult)
        if (first == null && !blasts) return
        removals.removedAll(source.world, if (blasts) event.blockList() else emptyList(), BlockRemoveCause.EXPLODE, event,
            first, blast = true)
    }

    /**
     * An ender dragon flying through blocks fires this event itself and then, at yield 0 (its default), removes every
     * listed block directly, TNT included; at any other yield (a plugin changed it) Paper's old TNTPrimeEvent decides
     * each listed block, so they come from [OldTntPrimeListener]. Other explosions prime listed TNT with the Bukkit
     * TNTPrimeEvent, which reports it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (removals.subs == 0 || !destroys(event.explosionResult)) return
        removals.events++
        val world = event.location.world ?: return
        if (event.entity is EnderDragon) {
            if (event.yield != 0f && oldPrime) return
            removals.removedAll(world, event.blockList(), BlockRemoveCause.EXPLODE, event)
            return
        }
        removals.removedAll(world, event.blockList(), BlockRemoveCause.EXPLODE, event, blast = true)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        if (!removals.watches(block.world)) return
        // TNT is reported by its TNTPrimeEvent (FIRE), which can still keep it; that also checks its neighbours.
        if (block.type == Material.TNT) return
        gone(block, BlockRemoveCause.BURN, event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (removals.subs == 0) return
        removals.events++
        gone(event.block, BlockRemoveCause.FADE, event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        if (removals.subs == 0) return
        removals.events++
        gone(event.block, BlockRemoveCause.DECAY, event)
    }

    /** The blocks a piston moves (at their original positions) and, on Paper, the ones it breaks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (removals.subs == 0) return
        removals.events++
        removals.removedAll(event.block.world, event.blocks, BlockRemoveCause.PISTON, event)
    }

    /**
     * The pulled blocks, and the piston's head: Paper removes it on every retract (a normal piston's event lists no
     * blocks at all). Not ignoreCancelled: a sticky piston that pulls blocks has its head cleared before this event
     * (in `moveBlocks`), and a cancelled pull doesn't put it back, so the head is reported either way; the pulled blocks
     * only when the event isn't cancelled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (removals.subs == 0) return
        removals.events++
        val head = head(event.block)
        if (event.isCancelled) {
            if (head != null && head.type.isAir) removals.removedAll(event.block.world, emptyList(), BlockRemoveCause.PISTON, event, head)
            return
        }
        removals.removedAll(event.block.world, event.blocks, BlockRemoveCause.PISTON, event, head)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDestroy(event: BlockDestroyEvent) {
        if (removals.subs == 0) return
        removals.events++
        gone(event.block, BlockRemoveCause.DESTROY, event)
    }

    /**
     * Only when the block isn't air and the entity turns it into another type. A change to air (or a fluid) only from
     * the paths that apply it themselves; the rest go on to `destroyBlock` and are reported as DESTROY. TNT comes from
     * its TNTPrimeEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        if (!removals.watches(block.world)) return
        val type = block.type
        val to = event.to
        if (type.isAir || type == Material.TNT || to == type) return
        val emptied = to.isAir || to == Material.WATER || to == Material.LAVA
        if (emptied && !(event.entity is Enderman || event.entity is FallingBlock || type == Material.CAKE)) return
        gone(block, BlockRemoveCause.ENTITY, event)
    }

    /**
     * TNT primed and removed. BLOCK_BREAK is left out: the break itself was reported (BREAK); priming doesn't change
     * that it goes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPrime(event: TNTPrimeEvent) {
        if (removals.subs == 0) return
        removals.events++
        val cause = when (event.cause) {
            TNTPrimeEvent.PrimeCause.EXPLOSION -> BlockRemoveCause.EXPLODE
            // Paper's old event comes next and can still keep the TNT: it reports it when it is there.
            TNTPrimeEvent.PrimeCause.FIRE -> if (oldPrime) return else BlockRemoveCause.BURN
            TNTPrimeEvent.PrimeCause.PROJECTILE -> BlockRemoveCause.ENTITY
            TNTPrimeEvent.PrimeCause.BLOCK_BREAK -> return
            else -> BlockRemoveCause.PRIME
        }
        gone(event.block, cause, event)
    }

    /**
     * Something spreads onto it and turns it into another block (sculk over stone, grass or mycelium over dirt). Spreads
     * into air (fire, vines, mushrooms) remove nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        val subs = removals.lookup(block) ?: return
        val type = block.type
        if (type.isAir || event.newState.type == type) return
        removals.deliver(block, subs, BlockRemoveCause.SPREAD, event)
    }

    /**
     * A player places a block over it (a block placed into short grass, a door into a flower's spot). Paper has already
     * set the new block when this event fires and puts the old one back when the event is cancelled or `canBuild()` is
     * false, so only accepted placements count, and the handler sees the replacement in the world. A multi-block
     * placement (bed, door) checks each replaced position. Placing into air removes nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (removals.subs == 0 || !event.canBuild()) return
        removals.events++
        if (event is BlockMultiPlaceEvent) {
            val states = event.replacedBlockStates
            var batch: ArrayList<Any>? = null
            for (i in 0 until states.size) {
                val block = states[i].block
                val subs = replaced(block, states[i].type, event) ?: continue
                batch = removals.collect(batch, block, subs)
            }
            removals.deliverBatch(batch ?: return, BlockRemoveCause.REPLACE, event)
        } else {
            val block = event.block
            val subs = replaced(block, event.blockReplacedState.type, event) ?: return
            removals.deliver(block, subs, BlockRemoveCause.REPLACE, event)
        }
    }

    /** The watchers of [block] when the placement replaced a non-air block of another material there, or null. */
    private fun replaced(block: Block, old: Material, event: Event): Array<RemovedSub>? {
        removals.checkNeighbours(block, event)
        val subs = removals.lookup(block) ?: return null
        if (old.isAir || block.type == old) return null
        return subs
    }

    /**
     * A block forms in its place (concrete powder hardening, lava turning to obsidian or cobblestone, water turning to
     * stone or freezing, frost walker ice): [BlockFormEvent], including Paper's [org.bukkit.event.block.EntityBlockFormEvent].
     * Skipped: forming into air (snow, dripstone growth), the same material, copper weathering (copper stays copper),
     * and concrete forming where no concrete powder is yet: Paper fires that while a player places powder into water,
     * before deciding the placement, which [onPlace] reports as REPLACE once accepted.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        val subs = removals.lookup(block) ?: return
        val type = block.type
        val to = event.newState.type
        if (type.isAir || to == type) return
        if (type.name.contains("COPPER") && to.name.contains("COPPER")) return
        if (to.name.endsWith("_CONCRETE") && !type.name.endsWith("_CONCRETE_POWDER")) return
        removals.deliver(block, subs, BlockRemoveCause.FORM, event)
    }

    /**
     * A sponge soaks up water and water plants: [SpongeAbsorbEvent.getBlocks] holds each position in its new state (air,
     * or no longer waterlogged). Positions whose material changes are reported; a block only losing its water stays.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAbsorb(event: SpongeAbsorbEvent) {
        if (removals.subs == 0) return
        removals.events++
        val world = event.block.world
        if (!removals.watches(world)) return
        val states = event.blocks
        var batch: ArrayList<Any>? = null
        for (i in 0 until states.size) {
            val state = states[i]
            val block = world.getBlockAt(state.x, state.y, state.z)
            val subs = removals.lookup(block) ?: continue
            if (block.type == state.type) continue
            batch = removals.collect(batch, block, subs)
        }
        removals.deliverBatch(batch ?: return, BlockRemoveCause.ABSORB, event)
    }

    /**
     * A fluid at this position recedes or changes into another block: [FluidLevelChangeEvent] whose new data is a
     * different material (flowing water drying up to air). A change of level within the same fluid isn't a removal.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFluidLevel(event: FluidLevelChangeEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        val subs = removals.lookup(block) ?: return
        val type = block.type
        if (type.isAir || event.newData.material == type) return
        removals.deliver(block, subs, BlockRemoveCause.FLUID, event)
    }

    /**
     * A player scoops it up with a bucket: water, lava, powder snow or a bubble column ([PlayerBucketFillEvent]; Paper
     * fires it before picking the block up, so the block is still there). A waterlogged block only loses its water and
     * stays, so it isn't reported.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (removals.subs == 0) return
        removals.events++
        val block = event.block
        if (!removals.watches(block.world)) return
        val type = block.type
        if (type != Material.WATER && type != Material.LAVA && type != Material.POWDER_SNOW && type != Material.BUBBLE_COLUMN) return
        gone(block, BlockRemoveCause.BUCKET, event)
    }

    /**
     * A dragon egg teleporting (Paper builds that event with face SELF): its old position. Otherwise fluid flowing into a
     * block washes it away, unless the target is air or a fluid (the flow just spreads) or waterloggable (water fills
     * it instead).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        if (removals.subs == 0) return
        removals.events++
        if (event.face == BlockFace.SELF) {
            val egg = event.block
            val subs = removals.lookup(egg) ?: return
            if (egg.type == Material.DRAGON_EGG) removals.deliver(egg, subs, BlockRemoveCause.TELEPORT, event)
            return
        }
        val target = event.toBlock
        val subs = removals.lookup(target) ?: return
        val type = target.type
        if (type.isAir || type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN) return
        if (target.blockData is Waterlogged) return
        removals.deliver(target, subs, BlockRemoveCause.FLUID, event)
    }

    /**
     * The head in front of a retracting piston [base], when it is watched and is this piston's head. The direction comes
     * from the base's own facing: Paper passes the piston's facing for a plain retract but the opposite for a sticky
     * piston's pull. By the sticky events the base is already a moving piston, which keeps the facing.
     */
    private fun head(base: Block): Block? {
        val facing = (base.blockData as? Directional)?.facing ?: return null
        val head = base.getRelative(facing)
        if (removals.lookup(head) == null) return null
        val type = head.type
        if (type == Material.PISTON_HEAD) return if ((head.blockData as? PistonHead)?.facing == facing) head else null
        // A sticky pull: moveBlocks has cleared the head already, and the base is a moving piston by then.
        return if (type.isAir && base.type == Material.MOVING_PISTON) head else null
    }

    internal companion object {
        private val GAME_MASTER = java.util.EnumSet.of(
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
            Material.JIGSAW, Material.TEST_BLOCK, Material.TEST_INSTANCE_BLOCK,
        )
        private val COMMAND_BLOCKS = java.util.EnumSet.of(
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
        )

        /**
         * Whether Paper 26.2 refuses [player]'s break of a [type] block after the BlockBreakEvent: a spectator, or a
         * game-master block without `canUseGameMasterBlocks` (creative and operator permission level 2, read here as
         * creative and op), except a command block for a creative player with `minecraft.commandblock`.
         */
        fun refused(player: Player, type: Material): Boolean {
            if (player.gameMode == GameMode.SPECTATOR) return true
            if (type !in GAME_MASTER) return false
            val creative = player.gameMode == GameMode.CREATIVE
            if (creative && player.isOp) return false
            return !(type in COMMAND_BLOCKS && creative && player.hasPermission("minecraft.commandblock"))
        }

        fun destroys(result: ExplosionResult): Boolean =
            result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY
    }
}

/**
 * Paper's old TNT prime event, in a listener of its own. Paper 26.2 fires it in two places: with reason EXPLOSION for
 * every block an ender dragon flies through at a non-zero yield (cancelling it keeps that block), and with reason FIRE
 * after the Bukkit TNTPrimeEvent for TNT that fire primes (cancelling it keeps the TNT unless fire already replaced it).
 * The event is deprecated for removal; [TriggerService] registers this listener separately and carries on without it once Paper
 * drops the class.
 */
@Suppress("DEPRECATION", "removal")
internal class OldTntPrimeListener(service: TriggerService) : Listener {
    private val removals = service.removals

    /**
     * Not ignoreCancelled: for FIRE, Paper may already have replaced the TNT with fire before this event, and then it
     * is gone even when the event is cancelled (the block holds the fire by now). Otherwise only uncancelled events.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onOldPrime(event: com.destroystokyo.paper.event.block.TNTPrimeEvent) {
        if (removals.subs == 0) return
        val cause = when (event.reason) {
            com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION -> BlockRemoveCause.EXPLODE
            com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE -> BlockRemoveCause.BURN
            else -> return
        }
        removals.events++
        val block = event.block
        if (event.isCancelled && (cause != BlockRemoveCause.BURN || block.type == Material.TNT)) return
        removals.checkNeighbours(block, event)
        val subs = removals.lookup(block) ?: return
        removals.deliver(block, subs, cause, event)
    }
}
