package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockRemoveCause
import com.sneakyutils.triggers.BlockRemovedHandler
import com.sneakyutils.triggers.engine.LongObjectMap
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Rail
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A block removal subscription ([com.sneakyutils.triggers.Triggers.onBlockRemoved]). */
internal class RemovedSub(
    service: TriggerService,
    owner: Plugin,
    @JvmField val key: BlockKey,
    @JvmField val handler: BlockRemovedHandler,
    label: String?,
) : SubBase(service, owner, KIND, label) {
    override fun release(silent: Boolean) = service.removals.remove(this)

    // The causes delivered this server tick, one bit per BlockRemoveCause.ordinal: a second delivery of a cause in the
    // same tick is dropped (Paper can report one removal twice in a tick, such as two SpongeAbsorbEvents for one sponge).
    @JvmField var causesTick = Int.MIN_VALUE
    @JvmField var causesThisTick = 0

    companion object {
        const val KIND = "block-removed"
    }
}

/**
 * Block removal index: `world UUID -> LongObjectMap(BlockKey.pack -> RemovedSub[])`. An event that touches no watched
 * block costs one primitive map lookup per block it touches (after one world lookup), and nothing at all while nothing
 * is subscribed. Arrays are copy-on-write: dispatch iterates a snapshot taken before any handler runs, so a
 * subscription made by a handler starts with the next event, and one closed meanwhile is skipped.
 *
 * The listener ([BlockRemovedListener]) is registered on the first subscription, so a server where nobody watches
 * removals doesn't even have Paper build these events for SneakyUtils.
 */
internal class RemovalIndex(private val service: TriggerService) {
    private val byPos = HashMap<UUID, LongObjectMap<Array<RemovedSub>>>()

    /** Active subscriptions: the listener returns at once when 0. */
    var subs = 0; private set

    // Always-on counters: events seen while something was subscribed, and blocks looked up for them.
    @JvmField var events = 0L
    @JvmField var lookups = 0L

    fun add(sub: RemovedSub) {
        val map = byPos.getOrPut(sub.key.worldId) { LongObjectMap() }
        val k = sub.key.asLong()
        val old = map[k]
        map.put(k, if (old == null) arrayOf(sub) else old + sub)
        subs++
    }

    fun remove(sub: RemovedSub) {
        val map = byPos[sub.key.worldId] ?: return
        val k = sub.key.asLong()
        val old = map[k] ?: return
        val i = old.indexOfFirst { it === sub }
        if (i < 0) return
        if (old.size == 1) map.remove(k) else map.put(k, old.filterIndexed { j, _ -> j != i }.toTypedArray())
        if (map.isEmpty) byPos.remove(sub.key.worldId)
        subs--
    }

    /** Subscriptions in one world, for world unload. Allocates. */
    fun subsInWorld(worldId: UUID): List<RemovedSub> {
        val out = ArrayList<RemovedSub>()
        byPos[worldId]?.forEachEntry { _, s -> out.addAll(s) }
        return out
    }

    /** The block position with the most subscriptions, described for `/triggers`, and the count; null when none. */
    fun densest(): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for ((world, map) in byPos) {
            map.forEachEntry { k, s ->
                if (s.size > (best?.second ?: 0)) {
                    best = "removal watch at ${BlockKey.unpackX(k)},${BlockKey.unpackY(k)},${BlockKey.unpackZ(k)} in " +
                        (service.plugin.server.getWorld(world)?.name ?: world.toString()) to s.size
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- dispatch

    // ---------------------------------------------------------------- blocks that remove themselves without an event
    // Rails drop and remove themselves in neighborChanged (dropResources + removeBlock, Paper 26.2) with no event when
    // their support goes. Redstone wire, repeaters and comparators do the same when the neighbour update comes first (a
    // block broken or blown up); when the shape update comes first (a piston moving the support) they go through the
    // server's destroy and fire BlockDestroyEvent instead. So when a removal event takes a block away, the watched
    // blocks of those kinds that rest on it are checked on the next tick, and reported as DESTROY when they have changed
    // (unless their own event reported them meanwhile). Scheduling is verified, not trusted.

    private class Pending(
        @JvmField val block: Block,
        @JvmField val before: Material,
        @JvmField val event: Event,
        @JvmField val subs: Array<RemovedSub>,
    )

    private var pending: LinkedHashMap<BlockKey, Pending>? = null
    private var pendingTask: org.bukkit.scheduler.BukkitTask? = null

    /** Neighbour checks scheduled, and those that found the neighbour gone (always-on counters). */
    @JvmField var neighbourChecks = 0L
    @JvmField var neighbourRemovals = 0L

    /** Whether any removal is watched in [world]: one map lookup. */
    fun watches(world: World): Boolean = byPos.isNotEmpty() && byPos.containsKey(world.uid)

    /**
     * [block] is being removed by [event]: schedules a next-tick check of the watched blocks that rest on it and drop
     * without an event: a rail, redstone wire, repeater or comparator above it, and a rail beside it that ascends
     * towards it (an ascending rail also rests on its uphill side). Up to five lookups, only while [block]'s world has
     * watches; a neighbour's type and shape are read only when it is watched.
     */
    fun checkNeighbours(block: Block, event: Event) {
        if (byPos.isEmpty()) return
        val world = block.world
        val map = byPos[world.uid] ?: return
        checkNeighboursIn(map, world, block.x, block.y, block.z, event)
    }

    private fun checkNeighboursIn(map: LongObjectMap<Array<RemovedSub>>, world: World, x: Int, y: Int, z: Int, event: Event) {
        queueIfResting(map, world, x, y + 1, z, event, null)
        for (d in 0 until 4) queueIfResting(map, world, x - SX[d], y, z - SZ[d], event, UPHILL[d])
    }

    /** Queues the watched block at x, y, z when it is one that drops without an event ([uphill]: a rail of that shape). */
    private fun queueIfResting(
        map: LongObjectMap<Array<RemovedSub>>, world: World, x: Int, y: Int, z: Int, event: Event, uphill: Rail.Shape?,
    ) {
        if (!BlockKey.isPackable(x, y, z)) return
        lookups++
        val subs = map[BlockKey.pack(x, y, z)] ?: return
        val nb = world.getBlockAt(x, y, z)
        val type = nb.type
        if (uphill == null) {
            if (type !in SELF_REMOVING) return
        } else if (type !in RAILS || (nb.blockData as? Rail)?.shape != uphill) {
            return
        }
        val p = pending ?: LinkedHashMap<BlockKey, Pending>().also { pending = it }
        // The first event this tick that took one of its supports is the one it is reported with.
        if (p.putIfAbsent(BlockKey(world.uid, x, y, z), Pending(nb, type, event, subs)) != null) return
        neighbourChecks++
        if (pendingTask == null && service.running) {
            pendingTask = service.plugin.server.scheduler.runTask(service.plugin, Runnable { runPending() })
        }
    }

    /** The next tick: reports each checked neighbour that changed, as DESTROY with the event that took its support. */
    internal fun runPending() {
        pendingTask = null
        val p = pending ?: return
        pending = null
        for ((key, e) in p) {
            if (service.plugin.server.getWorld(key.worldId) == null) continue
            if (e.block.type == e.before) continue
            neighbourRemovals++
            // The subscriptions watching when its support went (closed ones are skipped; later ones wait).
            deliver(e.block, e.subs, BlockRemoveCause.DESTROY, e.event)
        }
    }

    /** SneakyUtils disables: drop the pending checks. */
    fun shutdown() {
        pendingTask?.cancel()
        pendingTask = null
        pending = null
    }

    /** A position reported by an event needs no neighbour check any more. */
    private fun reported(block: Block) {
        val p = pending ?: return
        if (p.isNotEmpty()) p.remove(BlockKey.of(block))
    }

    /** The watchers of [block], or null: the one lookup an unwatched block costs. */
    fun lookup(block: Block): Array<RemovedSub>? {
        if (byPos.isEmpty()) return null
        val map = byPos[block.world.uid] ?: return null
        return lookupIn(map, block)
    }

    private fun lookupIn(map: LongObjectMap<Array<RemovedSub>>, block: Block): Array<RemovedSub>? {
        lookups++
        val x = block.x
        val y = block.y
        val z = block.z
        if (!BlockKey.isPackable(x, y, z)) return null
        return map[BlockKey.pack(x, y, z)]
    }

    /** One block removed; [subs] from [lookup]. */
    fun deliver(block: Block, subs: Array<RemovedSub>, cause: BlockRemoveCause, event: Event) {
        service.refreshTiming()
        service.stage(TriggerService.STAGE_REMOVED, "block.removed") { dispatch(block, subs, cause, event) }
    }

    /**
     * Every block in [blocks] removed by one event in [world], after [first] when not null. A position listed twice is
     * delivered once, at its first place. List entries are resolved in [world] by their coordinates, as Paper applies
     * them (a plugin may have added a block of another world), and air in the list is skipped. With [blast] (the targets
     * of a server explosion), TNT is left out (its TNTPrimeEvent reports it, see [BlockRemovedListener]) and so are the
     * blocks Paper never lets an explosion destroy ([INDESTRUCTIBLE]). [first] is delivered as it is: it may already be
     * gone (an exploded bed, a cleared piston head).
     */
    fun removedAll(
        world: World,
        blocks: List<Block>,
        cause: BlockRemoveCause,
        event: Event,
        first: Block? = null,
        blast: Boolean = false,
    ) {
        if (byPos.isEmpty()) return
        val worldId = world.uid
        val map = byPos[worldId] ?: return
        // Look every block up before any handler runs, so a subscription a handler makes waits for the next event.
        // Indexed loop: no iterator, and nothing allocated unless a block is watched.
        var hits: ArrayList<Any>? = null
        var seen: HashSet<Long>? = null
        val n = blocks.size
        for (i in -1 until n) {
            var b = if (i < 0) first ?: continue else blocks[i]
            if (i >= 0) {
                if (b.world.uid != worldId) b = world.getBlockAt(b.x, b.y, b.z)
                if (blast) {
                    // Only what the blast really takes: not air, not TNT (its prime event decides it), not the blocks
                    // Paper protects. Read for every entry, but only while this world has watches.
                    val type = b.type
                    if (type.isAir || type == Material.TNT || type in INDESTRUCTIBLE) continue
                }
            }
            // Every position the event takes (watched or not) may have held up a rail or wire; verified next tick.
            checkNeighboursIn(map, world, b.x, b.y, b.z, event)
            val subs = lookupIn(map, b) ?: continue
            val key = BlockKey.pack(b.x, b.y, b.z)
            val s = seen ?: HashSet<Long>().also { seen = it }
            if (!s.add(key)) continue
            if (i >= 0 && !blast && b.type.isAir) continue
            val h = hits ?: ArrayList<Any>(4).also { hits = it }
            h.add(b)
            h.add(subs)
        }
        deliverBatch(hits ?: return, cause, event)
    }

    /**
     * Adds a watched position and its subscription snapshot to [batch] (made on the first one), for events that remove
     * several positions: look all of them up first, then [deliverBatch], so a subscription a handler makes waits for
     * the next event.
     */
    fun collect(batch: ArrayList<Any>?, block: Block, subs: Array<RemovedSub>): ArrayList<Any> {
        val b = batch ?: ArrayList(4)
        b.add(block)
        b.add(subs)
        return b
    }

    /** Delivers what [collect] gathered, in order. */
    fun deliverBatch(batch: ArrayList<Any>, cause: BlockRemoveCause, event: Event) {
        service.refreshTiming()
        service.stage(TriggerService.STAGE_REMOVED, "block.removed") {
            var i = 0
            while (i < batch.size) {
                @Suppress("UNCHECKED_CAST")
                dispatch(batch[i] as Block, batch[i + 1] as Array<RemovedSub>, cause, event)
                i += 2
            }
        }
    }

    private fun dispatch(block: Block, subs: Array<RemovedSub>, cause: BlockRemoveCause, event: Event) {
        reported(block)
        val tick = service.plugin.server.currentTick
        for (sub in subs) {
            if (sub.closed) continue
            if (sub.causesTick != tick) {
                sub.causesTick = tick
                sub.causesThisTick = 0
            }
            val bit = 1 shl cause.ordinal
            if ((sub.causesThisTick and bit) != 0) continue
            sub.causesThisTick = sub.causesThisTick or bit
            service.guard(sub) { sub.handler.onRemoved(block, cause, event) }
        }
    }

    companion object {
        /** Blocks that remove themselves without an event when their support goes (see [checkNeighbours]). */
        @JvmField
        val SELF_REMOVING: java.util.EnumSet<Material> = java.util.EnumSet.of(
            Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL,
            Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR,
        )

        private val RAILS: java.util.EnumSet<Material> =
            java.util.EnumSet.of(Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL)

        // East, west, south, north: a rail one step the other way rests on the removed block when it ascends this way.
        private val SX = intArrayOf(1, -1, 0, 0)
        private val SZ = intArrayOf(0, 0, 1, -1)
        private val UPHILL = arrayOf(
            Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_NORTH,
        )

        /**
         * Blocks Paper 26.2 never lets an explosion destroy (`Block.isDestroyable`, unless the unsupported setting
         * `allow-permanent-block-break-exploits` is on): they stay even when a plugin adds them to a blast's list.
         */
        @JvmField
        val INDESTRUCTIBLE: java.util.EnumSet<Material> = java.util.EnumSet.of(
            Material.BARRIER, Material.BEDROCK, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.END_GATEWAY,
            Material.COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
            Material.JIGSAW,
        )
    }
}
