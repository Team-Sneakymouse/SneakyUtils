package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The movement engine, free of Bukkit server objects: positions come in as a world UUID and doubles, decisions go
 * out through [EngineSink] via the [EventQueue]. The Bukkit listener feeds it; tests drive it directly.
 *
 * A position report runs, in order, stopping as soon as there is nothing to do:
 * 1. Same world and same block as the last report: return (dedupe; the move event and the mounted poll can't
 *    double-fire a step).
 * 2. Store the new block and position. If the chunk or the area index version changed, re-read the chunk's areas
 *    (one map lookup).
 * 3. No watches, no areas in this chunk, no memberships, and no pass-through area near a chunk-crossing move:
 *    return. This is the no-op path: no allocation.
 * 4. Queue step and block watch events.
 * 5. Evaluate areas: exits for memberships the player left, enters for this chunk's areas the player is now in,
 *    pass-through (enter then exit) for areas the move crossed, and step events inside.
 * 6. Drain the queue: handlers run now, after all state is updated.
 *
 * Membership changes are applied before any handler runs. Handlers may call back in (teleport, kick, subscribe):
 * those calls update state at once and queue behind the current events (see [EventQueue]). Area predicates run during
 * evaluation, so they must not call back in: position reports from inside one are dropped and counted, and
 * registering or closing from inside one throws.
 *
 * Server thread only.
 */
internal class MovementCore(
    private val index: AreaIndex,
    private val sink: EngineSink,
    /** A single move longer than this counts as a teleport: no path checks, cause TELEPORT. */
    val maxSegmentBlocks: Int = DEFAULT_MAX_SEGMENT_BLOCKS,
    maxEventsPerDrain: Int = EventQueue.DEFAULT_MAX_PER_DRAIN,
) {
    val queue = EventQueue(sink, maxEventsPerDrain)

    /** Online players in join order: calls for several players (a registration, a close) go in this order. */
    private val players = LinkedHashMap<UUID, PlayerState>()
    private val watchesByPlayer = HashMap<UUID, PlayerWatches>()

    private var mounted = arrayOfNulls<PlayerState>(16)
    var mountedCount = 0
        private set
    private var pollBuffer = arrayOfNulls<PlayerState>(16)

    private val maxSegmentSq = maxSegmentBlocks.toDouble() * maxSegmentBlocks
    private var stamp = 0L
    private var evaluating = false
    private val chunkBuffer = LongArray(CHUNK_BUFFER)
    private val rangeBuffer = DoubleArray(2)

    // Always-on counters (plain longs: no timing, no allocation).
    @JvmField var steps = 0L
    @JvmField var noopSteps = 0L
    @JvmField var longMoves = 0L
    @JvmField var enters = 0L
    @JvmField var exits = 0L
    @JvmField var passThroughs = 0L
    @JvmField var reentrantDrops = 0L
    @JvmField var predicateErrors = 0L

    val playerCount: Int get() = players.size

    fun state(uuid: UUID): PlayerState? = players[uuid]

    /** The most watches one player has (online or kept for a rejoin). Scans. */
    fun maxWatchesPerPlayer(): Int = watchesByPlayer.values.maxOfOrNull { it.steps.size + it.blocks.size } ?: 0

    /** Players (online or not) with at least one watch; for leak tests. */
    internal val watchedPlayerCount: Int get() = watchesByPlayer.size

    /** Online players' states; allocates a copy. */
    fun states(): List<PlayerState> = ArrayList(players.values)

    // ---------------------------------------------------------------- position reports

    /** A move or teleport for a tracked player. Untracked players (not joined) are ignored. */
    fun position(uuid: UUID, worldId: UUID, x: Double, y: Double, z: Double, teleport: Boolean) {
        val s = players[uuid] ?: return
        position(s, worldId, x, y, z, teleport)
    }

    fun position(s: PlayerState, worldId: UUID, x: Double, y: Double, z: Double, teleport: Boolean) {
        if (s.removed) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (evaluating) {
            reentrantDrops++
            return
        }
        val nbx = Geometry.blockOf(x)
        val nby = Geometry.blockOf(y)
        val nbz = Geometry.blockOf(z)
        if (worldId == s.worldId) {
            if (nbx == s.bx && nby == s.by && nbz == s.bz) return
            moveWithinWorld(s, nbx, nby, nbz, x, y, z, teleport)
        } else {
            changeWorld(s, worldId, nbx, nby, nbz, x, y, z)
        }
        queue.drain()
    }

    private fun moveWithinWorld(s: PlayerState, nbx: Int, nby: Int, nbz: Int, x: Double, y: Double, z: Double, teleport: Boolean) {
        val fbx = s.bx
        val fby = s.by
        val fbz = s.bz
        val fx = s.px
        val fy = s.py
        val fz = s.pz
        val fromKey = s.keyObj
        s.bx = nbx
        s.by = nby
        s.bz = nbz
        s.px = x
        s.py = y
        s.pz = z
        s.keyObj = null
        val ck = Geometry.chunkKey(nbx shr 4, nbz shr 4)
        val crossedChunk = ck != s.chunkKey
        if (crossedChunk || s.indexVersion != index.version) refreshAreas(s, ck)
        steps++
        if (s.dead) return

        val w = s.watches
        if (w == null && s.chunkAreas == null && s.memberCount == 0 &&
            !(crossedChunk && !teleport && passThroughNear(s.worldAreas, fx, fz, x, z))
        ) {
            noopSteps++
            return
        }

        var tp = teleport
        if (!tp) {
            val dx = x - fx
            val dy = y - fy
            val dz = z - fz
            if (dx * dx + dy * dy + dz * dz > maxSegmentSq) {
                tp = true
                longMoves++
            }
        }
        if (w != null) queueWatches(s, w, fromKey, s.worldId!!, fbx, fby, fbz, tp)
        val cause = if (tp) AreaCause.TELEPORT else if (s.mounted) AreaCause.VEHICLE else AreaCause.MOVE
        evaluate(s, fx, fy, fz, crossedChunk && !tp, cause, MODE_STEP)
    }

    /** World change (also a player's first position after a respawn into another world). */
    private fun changeWorld(s: PlayerState, worldId: UUID, nbx: Int, nby: Int, nbz: Int, x: Double, y: Double, z: Double) {
        val fromWorld = s.worldId
        val fbx = s.bx
        val fby = s.by
        val fbz = s.bz
        val fromKey = s.keyObj
        exitAll(s, AreaCause.WORLD_CHANGE)
        endWatches(s.uuid) { it.options.keepOnWorldChange }
        setPosition(s, worldId, nbx, nby, nbz, x, y, z)
        steps++
        if (s.dead) return
        val w = s.watches
        if (w != null && fromWorld != null) queueWatches(s, w, fromKey, fromWorld, fbx, fby, fbz, true)
        evaluate(s, x, y, z, false, AreaCause.WORLD_CHANGE, MODE_ENTRY)
    }

    private fun setPosition(s: PlayerState, worldId: UUID, nbx: Int, nby: Int, nbz: Int, x: Double, y: Double, z: Double) {
        s.worldId = worldId
        s.bx = nbx
        s.by = nby
        s.bz = nbz
        s.px = x
        s.py = y
        s.pz = z
        s.keyObj = null
        s.indexVersion = -1 // force the world lookup
        refreshAreas(s, Geometry.chunkKey(nbx shr 4, nbz shr 4))
    }

    private fun refreshAreas(s: PlayerState, chunkKey: Long) {
        s.chunkKey = chunkKey
        val v = index.version
        if (s.indexVersion != v) {
            s.worldAreas = index.world(s.worldId!!)
            s.indexVersion = v
        }
        s.chunkAreas = s.worldAreas?.chunks?.get(chunkKey)
    }

    /** Whether the world has pass-through areas whose union box meets the move's horizontal bounding box. */
    private fun passThroughNear(wa: WorldAreas?, fx: Double, fz: Double, x: Double, z: Double): Boolean {
        if (wa == null || wa.passThroughCount == 0) return false
        val minX = if (fx < x) fx else x
        val maxX = if (fx < x) x else fx
        val minZ = if (fz < z) fz else z
        val maxZ = if (fz < z) z else fz
        return maxX >= wa.ptMinX && minX < wa.ptMaxX + 1.0 && maxZ >= wa.ptMinZ && minZ < wa.ptMaxZ + 1.0
    }

    // ---------------------------------------------------------------- watches

    private fun queueWatches(
        s: PlayerState, w: PlayerWatches, fromKey: BlockKey?, fromWorld: UUID,
        fbx: Int, fby: Int, fbz: Int, teleport: Boolean,
    ) {
        if (fromWorld == s.worldId && fbx == s.bx && fby == s.by && fbz == s.bz) return // respawn on the same block
        val mounted = s.mounted
        var from = fromKey
        var to: BlockKey? = null
        for (sw in w.steps) {
            if (!sw.active || !sw.accepts(mounted, teleport)) continue
            if (to == null) {
                to = BlockKey(s.worldId!!, s.bx, s.by, s.bz)
                s.keyObj = to
                if (from == null) from = BlockKey(fromWorld, fbx, fby, fbz)
            }
            queue.step(s, sw, from!!, to, mounted)
        }
        // Block types are read lazily, once per offset per step, and only when a block watch counts this step.
        var feetType: Material? = null
        var belowType: Material? = null
        var belowY = 0
        for (bw in w.blocks) {
            if (!bw.active || !bw.accepts(mounted, teleport)) continue
            val type: Material
            val y: Int
            if (bw.offset == BlockOffset.FEET) {
                if (feetType == null) feetType = sink.typeAt(s, s.bx, s.by, s.bz)
                type = feetType
                y = s.by
            } else {
                if (belowType == null) {
                    // Just below the feet; when that is air, the block below it (fence and wall tops).
                    belowY = floor(s.py - UNDER_EPSILON).toInt()
                    belowType = sink.typeAt(s, s.bx, belowY, s.bz)
                    if (isAir(belowType)) {
                        belowY--
                        belowType = sink.typeAt(s, s.bx, belowY, s.bz)
                    }
                }
                type = belowType
                y = belowY
            }
            val matches = bw.materials.contains(type)
            val was = bw.lastMatched
            bw.lastMatched = matches
            if (matches) {
                queue.block(s, bw, s.worldId!!, s.bx, y, s.bz, if (was) BlockMatch.STAYED else BlockMatch.ENTERED)
            } else if (was && bw.reportLeft) {
                queue.block(s, bw, s.worldId!!, s.bx, y, s.bz, BlockMatch.LEFT)
            }
        }
    }

    /** Adds a watch; it applies from the player's next step. The player may be offline (keepOnQuit watches). */
    fun addWatch(watch: Watch) {
        check(!evaluating) { "can't add a watch from inside an area predicate" }
        check(!watch.active) { "watch already added" }
        val w = watchesByPlayer.getOrPut(watch.playerId) { PlayerWatches(watch.playerId) }
        when (watch) {
            is StepWatch -> w.steps = w.steps + watch
            is BlockWatch -> w.blocks = w.blocks + watch
        }
        watch.active = true
        players[watch.playerId]?.watches = w
    }

    /**
     * Throws when the engine can't take a subscription change now (inside an area predicate). Callers that change
     * their own state before calling into the engine check this first, so a refused change leaves nothing half done.
     */
    fun checkMutable(what: String) {
        check(!evaluating) { "can't $what from inside an area predicate" }
    }

    /**
     * Removes a watch; does nothing if it already ended. [silent] (owner or SneakyUtils disable) is allowed inside an
     * area predicate: evaluation doesn't read watches, so the removal is safe there.
     */
    fun removeWatch(watch: Watch, silent: Boolean = false) {
        if (!silent) checkMutable("close a watch")
        if (!watch.active) return
        watch.active = false
        val w = watchesByPlayer[watch.playerId] ?: return
        when (watch) {
            is StepWatch -> w.steps = without(w.steps, watch)
            is BlockWatch -> w.blocks = without(w.blocks, watch)
        }
        if (w.isEmpty) {
            watchesByPlayer.remove(watch.playerId)
            players[watch.playerId]?.watches = null
        }
    }

    /** Ends the player's watches that [keep] rejects, telling the sink. */
    private inline fun endWatches(playerId: UUID, keep: (Watch) -> Boolean) {
        val w = watchesByPlayer[playerId] ?: return
        var ended: ArrayList<Watch>? = null
        for (sw in w.steps) if (!keep(sw)) (ended ?: ArrayList<Watch>().also { ended = it }).add(sw)
        for (bw in w.blocks) if (!keep(bw)) (ended ?: ArrayList<Watch>().also { ended = it }).add(bw)
        val list = ended ?: return
        w.steps = w.steps.filter { keep(it) }.toTypedArray()
        w.blocks = w.blocks.filter { keep(it) }.toTypedArray()
        if (w.isEmpty) {
            watchesByPlayer.remove(playerId)
            players[playerId]?.watches = null
        }
        for (watch in list) {
            watch.active = false
            sink.watchEnded(watch)
        }
    }

    // ---------------------------------------------------------------- areas

    /**
     * Indexes [reg] and counts players already inside as members: announced with [AreaCause.REGISTER] when
     * fireOnRegister is set, silently otherwise (they get no enter and no exit for this stay).
     */
    fun addArea(reg: AreaReg) {
        check(!evaluating) { "can't register an area from inside an area predicate" }
        index.add(reg)
        val a = reg.area
        val st = ++stamp
        evaluating = true
        try {
            for (s in players.values) {
                if (s.worldId != a.worldId || s.dead || !a.boxContains(s.bx, s.by, s.bz)) continue
                if (!mayEnter(reg, s) || !predicate(reg, s.px, s.py, s.pz)) continue
                val silent = !reg.options.fireOnRegister
                addMember(s, reg, silent, st)
                if (!silent) {
                    queue.enter(s, reg, AreaCause.REGISTER)
                    enters++
                }
            }
        } finally {
            endEvaluation()
        }
        queue.drain()
    }

    /**
     * Unindexes [reg] and ends every membership. Announced members get an [AreaCause.UNREGISTER] exit, unless
     * [silent] (owner or SneakyUtils disable), which also drops the area's queued enters and exits.
     */
    fun removeArea(reg: AreaReg, silent: Boolean) {
        if (evaluating) {
            // Only a silent removal can come from inside a predicate (a plugin disabled there): it must happen, so it
            // is silenced now and unindexed as soon as the evaluation ends. An explicit close is refused.
            check(silent) { "can't close an area from inside an area predicate" }
            reg.silenced = true
            if (reg.active) deferredRemovals.add(reg)
            return
        }
        unindex(reg, silent)
        queue.drain()
    }

    /** Silent removals asked for during an evaluation, done when it ends. */
    private val deferredRemovals = ArrayList<AreaReg>(0)

    /** Ends the evaluation: clears the flag and does the removals deferred during it. Queues, doesn't drain. */
    private fun endEvaluation() {
        evaluating = false
        if (deferredRemovals.isNotEmpty()) {
            for (i in deferredRemovals.indices) unindex(deferredRemovals[i], true)
            deferredRemovals.clear()
        }
        if (refreshWhenDone) {
            refreshWhenDone = false
            refreshStaleCaches()
        }
    }

    private fun unindex(reg: AreaReg, silent: Boolean) {
        if (silent) reg.silenced = true
        if (!reg.active) return
        index.remove(reg)
        if (reg.members > 0) {
            for (s in players.values) {
                var i = 0
                while (i < s.memberCount) {
                    if (s.members[i] !== reg) {
                        i++
                        continue
                    }
                    val wasSilent = s.memberSilent[i]
                    removeMemberAt(s, i)
                    if (!silent && !wasSilent) {
                        queue.exit(s, reg, AreaCause.UNREGISTER)
                        exits++
                    }
                }
            }
        }
    }

    /**
     * Area evaluation at the player's stored position. [mode]: [MODE_STEP] (a block step: path checks and step
     * events), [MODE_ENTRY] (join, world change, respawn: enters only), [MODE_REFRESH] (riding or game mode changed
     * on the same block: enters only).
     */
    private fun evaluate(s: PlayerState, fx: Double, fy: Double, fz: Double, walkPath: Boolean, cause: AreaCause, mode: Int) {
        val st = ++stamp
        val x = s.px
        val y = s.py
        val z = s.pz
        evaluating = true
        try {
            // 1. Exits: memberships whose area no longer contains the player.
            var i = 0
            while (i < s.memberCount) {
                val reg = s.members[i]!!
                reg.visitStamp = st
                if (reg.active && contains(reg, s, x, y, z)) {
                    i++
                    continue
                }
                val silent = s.memberSilent[i]
                removeMemberAt(s, i)
                if (!silent && reg.active) {
                    queue.exit(s, reg, cause)
                    exits++
                }
            }
            // 2. Enters (and pass-through) for the areas of the current chunk.
            val here = s.chunkAreas
            if (here != null) {
                for (reg in here) {
                    if (reg.visitStamp == st) continue
                    reg.visitStamp = st
                    consider(s, reg, fx, fy, fz, x, y, z, cause, mode, st)
                }
            }
            // 3. Pass-through areas in the other chunks along the path.
            if (walkPath && mode == MODE_STEP) walkPath(s, fx, fy, fz, x, y, z, cause, st)
            // 4. Steps inside, for memberships older than this evaluation.
            if (mode == MODE_STEP) {
                for (j in 0 until s.memberCount) {
                    if (s.memberSilent[j] || s.memberSince[j] == st) continue
                    val reg = s.members[j]!!
                    if (reg.options.stepEvents) queue.areaStep(s, reg, cause)
                }
            }
        } finally {
            endEvaluation()
        }
    }

    private fun consider(
        s: PlayerState, reg: AreaReg,
        fx: Double, fy: Double, fz: Double, x: Double, y: Double, z: Double,
        cause: AreaCause, mode: Int, st: Long,
    ) {
        if (!reg.active) return
        if (contains(reg, s, x, y, z)) {
            if (!mayEnter(reg, s)) return
            val silent = mode == MODE_ENTRY && cause == AreaCause.JOIN && !reg.options.fireOnJoin
            addMember(s, reg, silent, st)
            if (!silent) {
                queue.enter(s, reg, cause)
                enters++
            }
        } else if (mode == MODE_STEP && cause != AreaCause.TELEPORT && reg.options.passThrough && mayEnter(reg, s) &&
            crosses(reg, fx, fy, fz, x, y, z)
        ) {
            queue.enter(s, reg, cause)
            queue.exit(s, reg, cause)
            enters++
            exits++
            passThroughs++
        }
    }

    private fun walkPath(s: PlayerState, fx: Double, fy: Double, fz: Double, x: Double, y: Double, z: Double, cause: AreaCause, st: Long) {
        val wa = s.worldAreas ?: return
        if (!passThroughNear(wa, fx, fz, x, z)) return
        val n = Geometry.segmentChunks(fx, fz, x, z, chunkBuffer)
        var count = 0
        for (k in 0 until n) {
            val key = chunkBuffer[k]
            if (key == s.chunkKey) continue // done in step 2
            val regs = wa.chunks[key] ?: continue
            for (reg in regs) {
                if (reg.visitStamp == st) continue
                reg.visitStamp = st
                if (!reg.options.passThrough) continue
                if (count == pathBuffer.size) pathBuffer = pathBuffer.copyOf(count * 2)
                pathBuffer[count++] = reg
            }
        }
        // In registration order, like the destination chunk's areas (chunk arrays keep registration order).
        if (count > 1) java.util.Arrays.sort(pathBuffer, 0, count, BY_REGISTRATION)
        for (i in 0 until count) {
            val reg = pathBuffer[i]!!
            pathBuffer[i] = null
            consider(s, reg, fx, fy, fz, x, y, z, cause, MODE_STEP, st)
        }
    }

    /** Pass-through candidates found along a path, reused (grows only when a path meets more than ever before). */
    private var pathBuffer = arrayOfNulls<AreaReg>(8)

    /**
     * Whether the move crosses the area for a positive length. Box: the slab test. Custom: the slab test on its box,
     * then the predicate sampled along the part of the move inside the box, at most every half block (bounded by
     * the segment limit).
     */
    private fun crosses(reg: AreaReg, fx: Double, fy: Double, fz: Double, x: Double, y: Double, z: Double): Boolean {
        val a = reg.area
        if (!Geometry.segmentHitsBox(fx, fy, fz, x, y, z, a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ, rangeBuffer)) {
            return false
        }
        if (!a.isCustom) return true
        val t0 = rangeBuffer[0]
        val t1 = rangeBuffer[1]
        val dx = x - fx
        val dy = y - fy
        val dz = z - fz
        val length = sqrt(dx * dx + dy * dy + dz * dz) * (t1 - t0)
        val samples = ceil(length * 2).toInt().coerceIn(1, maxSegmentBlocks * 2 + 1)
        for (i in 0 until samples) {
            val t = t0 + (t1 - t0) * ((i + 0.5) / samples)
            if (predicate(reg, fx + dx * t, fy + dy * t, fz + dz * t)) return true
        }
        return false
    }

    private fun contains(reg: AreaReg, s: PlayerState, x: Double, y: Double, z: Double): Boolean =
        reg.area.boxContains(s.bx, s.by, s.bz) && predicate(reg, x, y, z)

    private fun predicate(reg: AreaReg, x: Double, y: Double, z: Double): Boolean {
        if (!reg.area.isCustom) return true
        return try {
            reg.area.predicateAccepts(x, y, z)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            predicateErrors++
            sink.predicateFailed(reg, t)
            false
        }
    }

    /** Riders and spectators gate entering only (see [com.sneakyutils.triggers.AreaOptions]). */
    private fun mayEnter(reg: AreaReg, s: PlayerState): Boolean {
        val o = reg.options
        if (s.spectator && !o.includeSpectators) return false
        return when (o.riders) {
            RiderPolicy.SKIP -> !s.mounted
            RiderPolicy.ALLOW -> true
            RiderPolicy.ONLY -> s.mounted
        }
    }

    private fun addMember(s: PlayerState, reg: AreaReg, silent: Boolean, st: Long) {
        val n = s.memberCount
        if (n == s.members.size) {
            val cap = if (n == 0) 4 else n * 2
            s.members = s.members.copyOf(cap)
            s.memberSilent = s.memberSilent.copyOf(cap)
            s.memberSince = s.memberSince.copyOf(cap)
        }
        s.members[n] = reg
        s.memberSilent[n] = silent
        s.memberSince[n] = st
        s.memberCount = n + 1
        reg.members++
    }

    /** Removes membership [i], keeping the others in the order they were entered (exits and steps follow it). */
    private fun removeMemberAt(s: PlayerState, i: Int) {
        val last = s.memberCount - 1
        val reg = s.members[i]!!
        val tail = last - i
        if (tail > 0) {
            System.arraycopy(s.members, i + 1, s.members, i, tail)
            System.arraycopy(s.memberSilent, i + 1, s.memberSilent, i, tail)
            System.arraycopy(s.memberSince, i + 1, s.memberSince, i, tail)
        }
        s.members[last] = null
        s.memberCount = last
        reg.members--
    }

    /** Ends every membership; announced ones get an exit with [cause]. */
    private fun exitAll(s: PlayerState, cause: AreaCause) {
        for (i in 0 until s.memberCount) {
            val reg = s.members[i]!!
            val silent = s.memberSilent[i]
            s.members[i] = null
            reg.members--
            if (!silent && reg.active) {
                queue.exit(s, reg, cause)
                exits++
            }
        }
        s.memberCount = 0
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Starts tracking a player at their join position; enters fire with [AreaCause.JOIN] (or silently). A [dead] player
     * enters nothing until [respawn].
     */
    fun join(
        uuid: UUID, player: Player?, worldId: UUID, x: Double, y: Double, z: Double,
        mounted: Boolean, spectator: Boolean, dead: Boolean = false,
    ): PlayerState {
        check(!evaluating) { "join from inside an area predicate" }
        if (players.containsKey(uuid)) quit(uuid) // a join without a quit: start clean
        val s = PlayerState(uuid)
        s.player = player
        s.spectator = spectator
        players[uuid] = s
        val w = watchesByPlayer[uuid]
        s.watches = if (w == null || w.isEmpty) null else w
        if (mounted) {
            s.mounted = true
            addMounted(s)
        }
        // On the death screen (joined or tracked while dead): no memberships until the respawn evaluates.
        s.dead = dead
        setPosition(s, worldId, Geometry.blockOf(x), Geometry.blockOf(y), Geometry.blockOf(z), x, y, z)
        if (!dead) evaluate(s, x, y, z, false, AreaCause.JOIN, MODE_ENTRY)
        queue.drain()
        return s
    }

    /** Stops tracking a player: exits with [AreaCause.QUIT]; watches end unless keepOnQuit. */
    fun quit(uuid: UUID) {
        val s = players.remove(uuid) ?: return
        exitAll(s, AreaCause.QUIT)
        removeMounted(s)
        s.removed = true
        s.watches = null
        endWatches(uuid) { it.options.keepOnQuit }
        queue.drain()
    }

    /** The player died: exits with [AreaCause.DEATH]; nothing fires for them until [respawn]. */
    fun death(uuid: UUID) {
        val s = players[uuid] ?: return
        if (s.dead) return
        s.dead = true
        exitAll(s, AreaCause.DEATH)
        queue.drain()
    }

    /**
     * The player respawned at this position: enters fire with [AreaCause.TELEPORT] (WORLD_CHANGE across worlds),
     * even when it is the block they died on.
     */
    fun respawn(uuid: UUID, worldId: UUID, x: Double, y: Double, z: Double) {
        val s = players[uuid] ?: return
        check(!evaluating) { "respawn from inside an area predicate" }
        s.dead = false
        val nbx = Geometry.blockOf(x)
        val nby = Geometry.blockOf(y)
        val nbz = Geometry.blockOf(z)
        if (worldId == s.worldId) {
            // Called even for the block they died on (no dedupe): death emptied the memberships, so this enters.
            moveWithinWorld(s, nbx, nby, nbz, x, y, z, true)
        } else {
            changeWorld(s, worldId, nbx, nby, nbz, x, y, z)
        }
        queue.drain()
    }

    /** Riding changed. Re-evaluates enters on the spot (riders gate entering), see [RiderPolicy]. */
    fun setMounted(s: PlayerState, isMounted: Boolean) {
        if (s.removed || s.mounted == isMounted) return
        check(!evaluating) { "mount change from inside an area predicate" }
        s.mounted = isMounted
        if (isMounted) addMounted(s) else removeMounted(s)
        refreshIfStale(s)
        if (!s.dead && s.worldId != null && s.chunkAreas != null) {
            evaluate(s, s.px, s.py, s.pz, false, if (isMounted) AreaCause.VEHICLE else AreaCause.MOVE, MODE_REFRESH)
            queue.drain()
        }
    }

    /** Spectator mode changed. Re-evaluates enters on the spot. */
    fun setSpectator(s: PlayerState, spectator: Boolean) {
        if (s.removed || s.spectator == spectator) return
        check(!evaluating) { "game mode change from inside an area predicate" }
        s.spectator = spectator
        refreshIfStale(s)
        if (!s.dead && s.worldId != null && s.chunkAreas != null) {
            evaluate(s, s.px, s.py, s.pz, false, if (s.mounted) AreaCause.VEHICLE else AreaCause.MOVE, MODE_REFRESH)
            queue.drain()
        }
    }

    /**
     * Re-reads every online player's cached areas that are older than the index, so a removed area isn't kept
     * reachable by players who don't move. One pass over the players: for rare events (a plugin disabling), not for
     * every change (a cache is re-read on the player's next block step anyway).
     */
    fun refreshStaleCaches() {
        if (evaluating) {
            refreshWhenDone = true // a plugin disabled inside a predicate: after the evaluation, like its removals
            return
        }
        for (s in players.values) refreshIfStale(s)
    }

    private var refreshWhenDone = false

    /** Re-reads the current chunk's areas if the index changed since they were cached (evaluations off a step). */
    private fun refreshIfStale(s: PlayerState) {
        if (s.worldId != null && s.indexVersion != index.version) refreshAreas(s, s.chunkKey)
    }

    private fun addMounted(s: PlayerState) {
        if (s.mountedIndex >= 0) return
        if (mountedCount == mounted.size) mounted = mounted.copyOf(mountedCount * 2)
        mounted[mountedCount] = s
        s.mountedIndex = mountedCount++
    }

    private fun removeMounted(s: PlayerState) {
        val i = s.mountedIndex
        if (i < 0) return
        val last = mounted[--mountedCount]!!
        mounted[i] = last
        last.mountedIndex = i
        mounted[mountedCount] = null
        s.mountedIndex = -1
    }

    /**
     * Copies the mounted players into a reusable buffer for the poll and returns how many. The poll iterates the
     * copy, so handlers that mount or dismount players during it can't disturb the iteration.
     */
    fun snapshotMounted(): Array<PlayerState?> {
        if (pollBuffer.size < mountedCount) pollBuffer = arrayOfNulls(mounted.size)
        System.arraycopy(mounted, 0, pollBuffer, 0, mountedCount)
        if (mountedCount < pollBuffer.size) pollBuffer[mountedCount] = null
        return pollBuffer
    }

    /** Drops everything without firing anything (SneakyUtils disable). */
    fun shutdown() {
        queue.closed = true
        queue.discard()
        for (reg in index.allRegs()) reg.silenced = true
        index.clear()
        for (w in watchesByPlayer.values) {
            for (sw in w.steps) sw.active = false
            for (bw in w.blocks) bw.active = false
        }
        watchesByPlayer.clear()
        for (s in players.values) {
            s.removed = true
            s.watches = null
        }
        players.clear()
        mounted.fill(null)
        mountedCount = 0
        pollBuffer.fill(null)
    }

    companion object {
        const val DEFAULT_MAX_SEGMENT_BLOCKS = 64
        const val MODE_STEP = 0
        const val MODE_ENTRY = 1
        const val MODE_REFRESH = 2

        /** Chunk keys for one path walk: a 64-block move needs at most 16 (see GeometryTest). */
        private const val CHUNK_BUFFER = 32
        private const val UNDER_EPSILON = 1.0e-3

        /** Registration order (see [AreaReg.seq]). */
        private val BY_REGISTRATION = Comparator<AreaReg?> { a, b -> a!!.seq.compareTo(b!!.seq) }

        private fun isAir(m: Material): Boolean = m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR

        private inline fun <reified T> without(array: Array<T>, item: T): Array<T> {
            val i = array.indexOf(item)
            if (i < 0) return array
            val out = arrayOfNulls<T>(array.size - 1)
            System.arraycopy(array, 0, out, 0, i)
            System.arraycopy(array, i + 1, out, i, array.size - i - 1)
            @Suppress("UNCHECKED_CAST")
            return out as Array<T>
        }
    }
}
