package com.sneakyutils.triggers.engine.model

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.WatchOptions
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import com.sneakyutils.triggers.engine.AreaIndex
import com.sneakyutils.triggers.engine.AreaReg
import com.sneakyutils.triggers.engine.BlockWatch
import com.sneakyutils.triggers.engine.EngineSink
import com.sneakyutils.triggers.engine.MovementCore
import com.sneakyutils.triggers.engine.PlayerState
import com.sneakyutils.triggers.engine.StepWatch
import com.sneakyutils.triggers.engine.Watch
import org.bukkit.Material
import java.util.IdentityHashMap
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/** The real engine core behind the same operations, recording what it delivers. */
internal class EngineSide : EngineSink {
    val index = AreaIndex()
    val core = MovementCore(index, this)
    val regs = HashMap<Int, AreaReg>()
    private val regIds = IdentityHashMap<AreaReg, Int>()
    val watches = HashMap<Int, Watch>()
    private val watchIds = IdentityHashMap<Watch, Int>()
    val delivered = ArrayList<Ev>()
    val ended = ArrayList<Int>()
    val hooks = Hooks { exec(it) }

    fun exec(op: Op) {
        when (op) {
            is Op.Join -> core.join(Fixed.uuid(op.p), null, Fixed.WORLDS[op.w], op.x, op.y, op.z, op.mounted, op.spectator, op.dead)
            is Op.Quit -> core.quit(Fixed.uuid(op.p))
            is Op.Pos -> core.position(Fixed.uuid(op.p), Fixed.WORLDS[op.w], op.x, op.y, op.z, op.teleport)
            is Op.Death -> core.death(Fixed.uuid(op.p))
            is Op.Respawn -> core.respawn(Fixed.uuid(op.p), Fixed.WORLDS[op.w], op.x, op.y, op.z)
            is Op.Mount -> core.state(Fixed.uuid(op.p))?.let { core.setMounted(it, op.on) }
            is Op.Spectate -> core.state(Fixed.uuid(op.p))?.let { core.setSpectator(it, op.on) }
            is Op.AddArea -> {
                if (regs.containsKey(op.id)) return
                val s = op.spec
                val area = if (s.shape != Shape.MEDDLE) s.area()
                else Area.custom(Fixed.WORLDS[s.world], s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ) { _, _, _ -> meddle(); true }
                val reg = AreaReg(area, s.options())
                regs[op.id] = reg
                regIds[reg] = op.id
                core.addArea(reg)
            }
            is Op.RemoveArea -> regs[op.id]?.let { core.removeArea(it, op.silent) }
            is Op.AddWatch -> {
                val s = op.spec
                if (watches.containsKey(op.id)) return
                if (core.state(Fixed.uuid(s.p)) == null && !s.keepOnQuit) return // as the service does
                val w: Watch = if (s.block) BlockWatch(Fixed.uuid(s.p), s.offset, s.materials, null, s.reportLeft, s.options())
                else StepWatch(Fixed.uuid(s.p), null, s.options())
                watches[op.id] = w
                watchIds[w] = op.id
                core.addWatch(w)
            }
            is Op.RemoveWatch -> watches[op.id]?.let { core.removeWatch(it) }
            is Op.AddHook -> hooks.add(op)
        }
    }

    /** Predicate calls that tried to change subscriptions, and changes that went through (must stay 0). */
    var meddles = 0L
    var meddleLeaks = 0L

    /** A predicate that breaks the rules: registers, adds a watch, closes an area and a watch. All must be refused. */
    private fun meddle() {
        meddles++
        val attempts = arrayOf<() -> Unit>(
            { core.addArea(AreaReg(Area.box(Fixed.WORLDS[0], 0, 0, 0, 1, 1, 1), AreaOptions.DEFAULT)) },
            { core.addWatch(StepWatch(Fixed.uuid(0), null, WatchOptions.DEFAULT)) },
            { core.removeArea(regs.values.firstOrNull { it.active } ?: throw IllegalStateException(), false) },
            { core.removeWatch(watches.values.firstOrNull { it.active } ?: throw IllegalStateException()) },
        )
        for (a in attempts) {
            try {
                a()
                meddleLeaks++
            } catch (e: IllegalStateException) {
                // refused, as it must be
            }
        }
    }

    private fun record(e: Ev) {
        delivered += e
        hooks.delivered(e)
    }

    private fun p(s: PlayerState) = Fixed.player(s.uuid)
    private fun key(w: UUID, x: Int, y: Int, z: Int) = "w${Fixed.world(w)} $x,$y,$z"

    override fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause) = record(Ev(Kind.ENTER, p(state), regIds[reg]!!, cause.name))
    override fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause) = record(Ev(Kind.EXIT, p(state), regIds[reg]!!, cause.name))
    override fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause) =
        record(Ev(Kind.AREA_STEP, p(state), regIds[reg]!!, cause.name))

    override fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean) =
        record(Ev(Kind.STEP, p(state), watchIds[watch]!!,
            "${key(from.worldId, from.x, from.y, from.z)} -> ${key(to.worldId, to.x, to.y, to.z)}" + if (mounted) " mounted" else ""))

    override fun blockWatch(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch) =
        record(Ev(Kind.BLOCK, p(state), watchIds[watch]!!, "${key(worldId, x, y, z)} $match"))

    override fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material = Fixed.typeAt(Fixed.world(state.worldId!!), x, y, z)
    override fun predicateFailed(reg: AreaReg, error: Throwable) = throw AssertionError("predicate threw", error)
    override fun watchEnded(watch: Watch) {
        ended += watchIds[watch]!!
    }

    override fun eventsDropped(count: Int, lastSubject: Any?) = throw AssertionError("the queue dropped $count events")
}

/** Runs a sequence on both sides, comparing after every top-level operation. */
internal class Runner(private val coverage: MutableMap<String, Long>? = null) {
    val engine = EngineSide()
    val model = ReferenceModel()

    /** Runs [op] on both sides; returns a description of the first difference, or null. */
    fun step(op: Op): String? {
        engine.delivered.clear()
        model.delivered.clear()
        try {
            engine.exec(op)
        } catch (t: Throwable) {
            return "engine threw ${t.stackTraceToString().lines().take(12).joinToString("\n")}"
        }
        model.top(op)
        if (coverage != null) {
            coverage.merge("op ${op.javaClass.simpleName}", 1L, Long::plus)
            for (e in model.delivered) coverage.merge(if (e.kind.isArea) "${e.kind} ${e.detail}" else "${e.kind}${if (e.kind == Kind.BLOCK) " " + e.detail.substringAfterLast(' ') else if (e.detail.endsWith("mounted")) " mounted" else ""}", 1L, Long::plus)
            val passes = model.delivered.zipWithNext().count { (a, b) -> a.kind == Kind.ENTER && b.kind == Kind.EXIT && a.subject == b.subject && a.player == b.player && a.detail == b.detail }
            if (passes > 0) coverage.merge("enter+exit pairs (pass-through)", passes.toLong(), Long::plus)
            coverage.merge("hooks fired", model.hooks.firedCount - hooksSeen, Long::plus)
            hooksSeen = model.hooks.firedCount
            coverage.merge("self-triggered skips", model.selfTriggerDrops - selfSeen, Long::plus)
            selfSeen = model.selfTriggerDrops
            coverage.merge("predicate meddling refused", engine.meddles - meddlesSeen, Long::plus)
            meddlesSeen = engine.meddles
        }
        return compareEvents(engine.delivered, model.delivered) ?: compareState()
    }

    private var hooksSeen = 0L
    private var selfSeen = 0L
    private var meddlesSeen = 0L

    private fun groupKey(e: Ev) = "${if (e.kind.isArea) "A" else "W"}${e.subject} p${e.player}"

    private fun compareEvents(e: List<Ev>, m: List<Ev>): String? {
        if (e == m) return null
        val es = e.map { it.toString() }.sorted()
        val ms = m.map { it.toString() }.sorted()
        // Order across areas and watches is not specified; per player and subscription it is.
        val sameOrderPerKey = e.groupBy { groupKey(it) } == m.groupBy { groupKey(it) }
        if (es == ms && sameOrderPerKey) return null
        return "events differ\n  engine: $e\n  model:  $m"
    }

    private fun compareState(): String? {
        val core = engine.core
        for (mp in model.players) {
            val s = core.state(Fixed.uuid(mp.id))
            if ((s != null) != mp.online) return "p${mp.id} online: engine ${s != null}, model ${mp.online}"
            if (s == null) continue
            val ew = Fixed.world(s.worldId!!)
            val e = "w$ew ${s.bx},${s.by},${s.bz} (${s.px}, ${s.py}, ${s.pz}) mounted=${s.mounted} spectator=${s.spectator} dead=${s.dead}"
            val m = "w${mp.world} ${mp.bx},${mp.by},${mp.bz} (${mp.x}, ${mp.y}, ${mp.z}) mounted=${mp.mounted} spectator=${mp.spectator} dead=${mp.dead}"
            if (e != m) return "p${mp.id} state:\n  engine: $e\n  model:  $m"
            val em = (0 until s.memberCount).associate { regIdOf(s.members[it]!!) to s.memberSilent[it] }.toSortedMap()
            val mm = mp.members.toSortedMap()
            if (em != mm) return "p${mp.id} members (id=silent): engine $em, model $mm"
            val hasWatches = model.watches.values.any { it.active && it.spec.p == mp.id }
            if ((s.watches != null) != hasWatches) return "p${mp.id} watches field ${s.watches != null}, active watches $hasWatches"
        }
        for ((id, a) in model.areas) {
            val reg = engine.regs[id]!!
            if (reg.active != a.active) return "A$id active: engine ${reg.active}, model ${a.active}"
            val count = model.players.count { it.online && it.members.containsKey(id) }
            if (reg.members != count) return "A$id member count: engine ${reg.members}, model $count"
        }
        for ((id, w) in model.watches) {
            val ew = engine.watches[id]!!
            if (ew.active != w.active) return "W$id active: engine ${ew.active}, model ${w.active}"
            if (ew is BlockWatch && ew.lastMatched != w.lastMatched) return "W$id lastMatched: engine ${ew.lastMatched}, model ${w.lastMatched}"
        }
        if (engine.ended.sorted() != model.ended.sorted()) return "ended watches: engine ${engine.ended}, model ${model.ended}"
        if (engine.meddleLeaks > 0) return "${engine.meddleLeaks} subscription changes from inside a predicate went through"
        if (core.queue.selfTriggerDrops != model.selfTriggerDrops) {
            return "self-triggered skips: engine ${core.queue.selfTriggerDrops}, model ${model.selfTriggerDrops}"
        }
        val mounted = model.players.count { it.online && it.mounted }
        if (core.mountedCount != mounted) return "mounted list: engine ${core.mountedCount}, model $mounted"
        val active = model.areas.values.count { it.active }
        if (engine.index.areaCount != active) return "indexed areas: engine ${engine.index.areaCount}, model $active"
        val watched = model.watches.values.filter { it.active }.map { it.spec.p }.toSet().size
        if (core.watchedPlayerCount != watched) return "players with watches: engine ${core.watchedPlayerCount}, model $watched"
        if (active == 0 && engine.index.chunkEntries != 0) return "chunk entries left with no areas: ${engine.index.chunkEntries}"
        return null
    }

    private fun regIdOf(reg: AreaReg): Int = engine.regs.entries.first { it.value === reg }.key

    companion object {
        /** Replays [ops] on fresh sides; the failure description and the index of the failing op, or null. */
        fun replay(ops: List<Op>): Pair<Int, String>? {
            val r = Runner()
            for ((i, op) in ops.withIndex()) {
                val f = r.step(op) ?: continue
                return i to f
            }
            return null
        }

        /**
         * Shrinks a failing sequence: drops operations (and hook actions) while it still fails. Operations that no
         * longer apply do nothing, so any subsequence is a valid sequence.
         */
        fun shrink(ops: List<Op>): List<Op> {
            var cur = ops.subList(0, (replay(ops) ?: return ops).first + 1).toList()
            var changed = true
            while (changed) {
                changed = false
                var chunk = maxOf(1, cur.size / 2)
                while (chunk >= 1) {
                    var i = cur.size - chunk
                    while (i >= 0) {
                        val candidate = cur.subList(0, i) + cur.subList(minOf(cur.size, i + chunk), cur.size)
                        val f = replay(candidate)
                        if (f != null) {
                            cur = candidate.subList(0, f.first + 1).toList()
                            changed = true
                            i = minOf(i, cur.size - chunk)
                        } else {
                            i--
                        }
                    }
                    chunk /= 2
                }
            }
            return cur
        }
    }
}

/**
 * Generates random sequences from a seed. It reads the model's state to aim moves at areas, chunk corners and box faces,
 * but records absolute operations, so a sequence replays exactly.
 */
internal class Generator(seed: Long) {
    private val r = SplittableRandom(seed)
    private var nextArea = 0
    private var nextWatch = 0
    private var nextHook = 0

    /** The playground: every position stays within [SPAN] blocks of this point (chunk corners, the world border...). */
    val origin: DoubleArray = ORIGINS[r.nextInt(ORIGINS.size)]

    fun sequence(model: ReferenceModel, length: Int, sink: (Op) -> Boolean) {
        // A few players and areas first, so most sequences interact.
        for (p in 0 until 1 + r.nextInt(Fixed.PLAYERS)) if (!sink(join(p))) return
        for (i in 0 until 2 + r.nextInt(4)) if (!sink(addArea(model))) return
        repeat(length) { if (!sink(next(model))) return }
    }

    fun next(model: ReferenceModel): Op {
        val online = model.players.filter { it.online }
        val p = if (online.isNotEmpty() && r.nextInt(10) < 9) online[r.nextInt(online.size)] else model.players[r.nextInt(Fixed.PLAYERS)]
        return when (r.nextInt(100)) {
            in 0..39 -> if (p.online) move(model, p) else join(p.id)
            in 40..45 -> teleport(model, p, sameWorld = true)
            in 46..48 -> teleport(model, p, sameWorld = false)
            in 49..52 -> join(p.id)
            in 53..55 -> Op.Quit(p.id)
            in 56..57 -> Op.Death(p.id)
            in 58..60 -> respawnOrDeath(model)
            in 61..66 -> Op.Mount(p.id, !p.mounted)
            in 67..69 -> Op.Spectate(p.id, !p.spectator)
            in 70..78 -> if (model.areas.values.count { it.active } < 24) addArea(model) else removeArea(model)
            in 79..82 -> removeArea(model)
            in 83..87 -> addWatch(model, p.id)
            in 88..90 -> removeWatch(model)
            else -> addHook(model)
        }
    }

    // ---------------------------------------------------------------- positions

    private fun grid(v: Double): Double = if (r.nextInt(8) == 0) v else round(v * 64.0) / 64.0

    private fun clampX(v: Double) = v.coerceIn(origin[0] - SPAN, origin[0] + SPAN)
    private fun clampY(v: Double) = v.coerceIn(origin[1] - SPAN, origin[1] + SPAN)
    private fun clampZ(v: Double) = v.coerceIn(origin[2] - SPAN, origin[2] + SPAN)

    private fun near(range: Double) = (r.nextDouble() * 2 - 1) * range

    private fun pos(p: Int, w: Int, x: Double, y: Double, z: Double, teleport: Boolean) =
        Op.Pos(p, w, clampX(grid(x)), clampY(grid(y)), clampZ(grid(z)), teleport)

    private fun randomPoint(): DoubleArray =
        doubleArrayOf(origin[0] + near(48.0), origin[1] + near(6.0), origin[2] + near(48.0))

    /** A point inside a random area of world [w] (or a random point when it has none). */
    private fun pointIn(model: ReferenceModel, w: Int): DoubleArray {
        val list = model.areas.values.filter { it.active && it.spec.world == w }
        if (list.isEmpty()) return randomPoint()
        val s = list[r.nextInt(list.size)].spec
        return doubleArrayOf(
            s.minX + r.nextDouble() * (s.maxX - s.minX + 1),
            s.minY + r.nextDouble() * (s.maxY - s.minY + 1),
            s.minZ + r.nextDouble() * (s.maxZ - s.minZ + 1),
        )
    }

    private fun join(p: Int): Op.Join {
        val q = if (r.nextBoolean()) randomPoint() else doubleArrayOf(origin[0] + near(8.0), origin[1] + near(2.0), origin[2] + near(8.0))
        val w = if (r.nextInt(6) == 0) 1 else 0
        return Op.Join(p, w, clampX(grid(q[0])), clampY(grid(q[1])), clampZ(grid(q[2])), r.nextInt(4) == 0, r.nextInt(8) == 0,
            dead = r.nextInt(10) == 0) // on the death screen (rejoin before respawning, or tracked when SneakyUtils enables)
    }

    private fun teleport(model: ReferenceModel, p: ReferenceModel.MP, sameWorld: Boolean): Op {
        val w = if (sameWorld || !p.online) maxOf(p.world, 0) else 1 - maxOf(p.world, 0)
        val q = when {
            r.nextBoolean() -> pointIn(model, w)
            r.nextBoolean() && p.online -> doubleArrayOf(p.x + near(30.0), p.y + near(5.0), p.z + near(30.0))
            else -> randomPoint()
        }
        return pos(p.id, w, q[0], q[1], q[2], r.nextInt(5) != 0) // sometimes a move event that lands in another world
    }

    private fun respawnOrDeath(model: ReferenceModel): Op {
        val dead = model.players.filter { it.online && it.dead }
        if (dead.isEmpty()) return Op.Death(r.nextInt(Fixed.PLAYERS))
        val p = dead[r.nextInt(dead.size)]
        val q = if (r.nextBoolean()) doubleArrayOf(p.x, p.y, p.z) else randomPoint()
        val w = if (r.nextInt(5) == 0) 1 - p.world else p.world
        return Op.Respawn(p.id, w, clampX(grid(q[0])), clampY(grid(q[1])), clampZ(grid(q[2])))
    }

    private fun move(model: ReferenceModel, p: ReferenceModel.MP): Op {
        val x = p.x
        val y = p.y
        val z = p.z
        val w = p.world
        fun along(len: Double, flat: Boolean = true): Op {
            val a = r.nextDouble() * Math.PI * 2
            val dy = if (flat) 0.0 else near(len / 3)
            return pos(p.id, w, x + Math.cos(a) * len, y + dy, z + Math.sin(a) * len, false)
        }
        return when (r.nextInt(15)) {
            0, 1 -> { // one block along an axis
                val d = if (r.nextBoolean()) 1.0 else -1.0
                when (r.nextInt(5)) {
                    0, 1 -> pos(p.id, w, x + d, y, z, false)
                    2, 3 -> pos(p.id, w, x, y, z + d, false)
                    else -> pos(p.id, w, x, y + d, z, false)
                }
            }
            2 -> pos(p.id, w, x + near(0.4), y, z + near(0.4), false) // often the same block: dropped
            3 -> along(2 + r.nextDouble() * 6)
            4 -> along(1.5 + r.nextDouble() * 2.5) // a vehicle at about 2 blocks a tick
            5 -> along(16 + r.nextDouble() * 46, flat = r.nextBoolean())
            6 -> when (r.nextInt(3)) { // the segment limit: exactly 64 is a move, a hair over is a teleport
                0 -> pos(p.id, w, x + 64.0, y, z, false)
                1 -> pos(p.id, w, x, y, z - 64.015625, false)
                else -> pos(p.id, w, x + 63.984375, y, z, false)
            }
            7 -> along(65 + r.nextDouble() * 200)
            8 -> throughChunkCorner(p)
            9, 10, 11 -> towardArea(model, p) ?: along(3.0)
            12 -> alongFace(model, p) ?: along(2.0)
            13 -> pos(p.id, w, x + near(2.0), y + near(40.0), z + near(2.0), false)
            else -> {
                val q = randomPoint()
                pos(p.id, w, q[0], q[1], q[2], false)
            }
        }
    }

    /** A straight move through the nearest chunk corner (both side chunks touched only at a point). */
    private fun throughChunkCorner(p: ReferenceModel.MP): Op {
        val cx = round(p.x / 16.0) * 16.0
        val cz = round(p.z / 16.0) * 16.0
        val vx = cx - p.x
        val vz = cz - p.z
        val len = sqrt(vx * vx + vz * vz)
        if (len < 1.0 / 64 || len > 30) return pos(p.id, p.world, cx - 3.0, p.y, cz - 3.0, true)
        var t = listOf(0.25, 0.5, 1.0, 2.0)[r.nextInt(4)]
        while ((1 + t) * len > 63.0 && t > 0.25) t /= 2
        return Op.Pos(p.id, p.world, cx + vx * t, p.y, cz + vz * t, false)
    }

    /** Into an area, or through it and out the far side (pass-through). */
    private fun towardArea(model: ReferenceModel, p: ReferenceModel.MP): Op? {
        val list = model.areas.values.filter { it.active && it.spec.world == p.world }
        if (list.isEmpty()) return null
        val s = list[r.nextInt(list.size)].spec
        val tx = s.minX + r.nextDouble() * (s.maxX - s.minX + 1)
        val ty = s.minY + r.nextDouble() * (s.maxY - s.minY + 1)
        val tz = s.minZ + r.nextDouble() * (s.maxZ - s.minZ + 1)
        if (r.nextBoolean()) return pos(p.id, p.world, tx, ty, tz, r.nextInt(6) == 0)
        val k = 1.0 + r.nextDouble() * 1.5
        return pos(p.id, p.world, p.x + (tx - p.x) * k, p.y + (ty - p.y) * k, p.z + (tz - p.z) * k, false)
    }

    /** Onto or along an area's faces: the inclusive min face, the exclusive max + 1 face, the edges. */
    private fun alongFace(model: ReferenceModel, p: ReferenceModel.MP): Op? {
        val list = model.areas.values.filter { it.active && it.spec.world == p.world }
        if (list.isEmpty()) return null
        val s = list[r.nextInt(list.size)].spec
        val fx = if (r.nextBoolean()) s.minX.toDouble() else s.maxX + 1.0
        val fz = if (r.nextBoolean()) s.minZ.toDouble() else s.maxZ + 1.0
        val y = s.minY + r.nextInt(maxOf(1, s.maxY - s.minY + 1)).toDouble()
        return when (r.nextInt(3)) {
            0 -> Op.Pos(p.id, p.world, fx, y, p.z + near(3.0), false)
            1 -> Op.Pos(p.id, p.world, p.x + near(3.0), y, fz, false)
            else -> Op.Pos(p.id, p.world, fx, y, fz, false)
        }
    }

    // ---------------------------------------------------------------- subscriptions

    private fun addArea(model: ReferenceModel): Op.AddArea = Op.AddArea(nextArea++, areaSpec(model))

    private fun areaSpec(model: ReferenceModel): AreaSpec {
        val online = model.players.filter { it.online }
        val anchor = if (online.isNotEmpty() && r.nextInt(6) != 0) online[r.nextInt(online.size)] else null
        val w = anchor?.world ?: (if (r.nextInt(3) == 0) 1 else 0)
        val c = if (anchor != null) doubleArrayOf(anchor.x + near(6.0), anchor.y + near(2.0), anchor.z + near(6.0)) else randomPoint()
        val (sx, sy, sz) = when (r.nextInt(10)) {
            0, 1, 2 -> Triple(r.nextInt(3), r.nextInt(4), r.nextInt(3))
            3, 4 -> Triple(3 + r.nextInt(15), 2 + r.nextInt(8), 3 + r.nextInt(15))
            5 -> if (r.nextBoolean()) Triple(0, 4 + r.nextInt(20), 8 + r.nextInt(40)) else Triple(8 + r.nextInt(40), 4 + r.nextInt(20), 0) // a wall
            6 -> Triple(5 + r.nextInt(30), 0, 5 + r.nextInt(30)) // a floor plate
            7 -> Triple(40 + r.nextInt(200), 6 + r.nextInt(20), 40 + r.nextInt(200)) // many chunks
            else -> Triple(r.nextInt(8), r.nextInt(8), r.nextInt(8))
        }
        val minX = floor(c[0] - sx / 2.0).toInt()
        val minY = floor(c[1] - sy / 2.0).toInt()
        val minZ = floor(c[2] - sz / 2.0).toInt()
        val shape = if (r.nextInt(5) == 0) Shape.entries[1 + r.nextInt(Shape.entries.size - 1)] else Shape.BOX
        return AreaSpec(
            w, minX, minY, minZ, minX + sx, minY + sy, minZ + sz,
            riders = RiderPolicy.entries[r.nextInt(3)],
            spectators = r.nextInt(3) == 0,
            // Custom shapes' pass-through samples the predicate (approximate by design): covered by unit tests.
            passThrough = shape == Shape.BOX && r.nextInt(3) == 0,
            fireOnJoin = r.nextInt(4) != 0,
            fireOnRegister = r.nextBoolean(),
            stepEvents = r.nextBoolean(),
            shape = shape,
            cx = minX + sx / 2.0, cz = minZ + sz / 2.0, r = 1.0 + (sx + sz) / 4.0,
        )
    }

    private fun removeArea(model: ReferenceModel): Op {
        if (model.areas.isEmpty()) return addArea(model)
        val ids = model.areas.keys.toList()
        return Op.RemoveArea(ids[r.nextInt(ids.size)], r.nextInt(4) == 0)
    }

    private fun addWatch(model: ReferenceModel, p: Int): Op.AddWatch {
        val block = r.nextBoolean()
        val mats = MATERIALS.filter { r.nextInt(3) == 0 }.ifEmpty { listOf(MATERIALS[r.nextInt(MATERIALS.size)]) }.toSet()
        return Op.AddWatch(nextWatch++, WatchSpec(
            p, block, if (r.nextBoolean()) BlockOffset.FEET else BlockOffset.BELOW, mats, r.nextInt(4) != 0,
            onFootOnly = r.nextInt(3) != 0, keepOnQuit = r.nextInt(4) == 0, keepOnWorldChange = r.nextInt(4) != 0,
            includeTeleports = r.nextInt(3) == 0,
        ))
    }

    private fun removeWatch(model: ReferenceModel): Op {
        if (model.watches.isEmpty()) return addWatch(model, r.nextInt(Fixed.PLAYERS))
        val ids = model.watches.keys.toList()
        return Op.RemoveWatch(ids[r.nextInt(ids.size)])
    }

    /** A handler that calls back in: teleports, moves, kicks, kills, mounts, subscribes or closes. */
    private fun addHook(model: ReferenceModel): Op {
        val kind = if (r.nextInt(5) == 0) null else Kind.entries[r.nextInt(Kind.entries.size)]
        val player = if (r.nextBoolean()) -1 else r.nextInt(Fixed.PLAYERS)
        val subjects = if (kind?.isArea != false) model.areas.keys else model.watches.keys
        val subject = if (subjects.isEmpty() || r.nextBoolean()) -1 else subjects.elementAt(r.nextInt(subjects.size))
        val target = model.players[if (player >= 0) player else r.nextInt(Fixed.PLAYERS)]
        val action: Op = when (r.nextInt(13)) {
            0, 1 -> teleport(model, target, sameWorld = true)
            2 -> if (target.online) move(model, target) else teleport(model, target, sameWorld = true)
            3 -> teleport(model, target, sameWorld = false)
            4 -> Op.Quit(target.id)
            5 -> Op.Death(target.id)
            6 -> Op.Mount(target.id, r.nextBoolean())
            7 -> Op.Spectate(target.id, r.nextBoolean())
            8 -> addArea(model)
            9 -> removeArea(model)
            10 -> addWatch(model, target.id)
            11 -> removeWatch(model)
            else -> {
                val q = randomPoint()
                Op.Respawn(target.id, maxOf(0, target.world), clampX(grid(q[0])), clampY(grid(q[1])), clampZ(grid(q[2])))
            }
        }
        return Op.AddHook(nextHook++, Trigger(kind, player, subject), action)
    }

    companion object {
        const val SPAN = 400.0
        private val MATERIALS = listOf(Material.WATER, Material.STONE, Material.SAND, Material.OAK_FENCE, Material.AIR)

        /**
         * Where sequences play: spawn, chunk corners, negative coordinates, the world border (+-30,000,000), the
         * BlockKey packing limit (2^25), far beyond the border (1e9) and next to the int limit, and both build-height
         * extremes of the 12-bit y range.
         */
        val ORIGINS = arrayOf(
            doubleArrayOf(0.0, 64.0, 0.0),
            doubleArrayOf(8.0, 64.0, 8.0),
            doubleArrayOf(-48.0, 64.0, 80.0),
            doubleArrayOf(-1.0, 0.0, -1.0),
            doubleArrayOf(29_999_984.0, 64.0, -29_999_984.0),
            doubleArrayOf(-30_000_000.0, 100.0, 30_000_000.0),
            doubleArrayOf(33_554_400.0, 64.0, -33_554_400.0),
            doubleArrayOf(1.0e9, 64.0, -1.0e9),
            doubleArrayOf(2_147_483_000.0, 64.0, -2_147_483_000.0),
            doubleArrayOf(0.0, -2040.0, 0.0),
            doubleArrayOf(0.0, 2040.0, 0.0),
            doubleArrayOf(-2_000_000.5, -64.0, 7.25),
        )
    }
}

internal fun mix(seed: Long, i: Long): Long {
    var z = seed + i * 0x632BE59BD9B4E019L
    z = (z xor (z ushr 30)) * 0x3C6EF372FE94F82BL
    z = (z xor (z ushr 27)) * 0x1C69B3F74AC4AE35L
    return z xor (z ushr 31)
}
