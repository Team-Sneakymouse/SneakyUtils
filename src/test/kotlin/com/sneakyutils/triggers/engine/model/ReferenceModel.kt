package com.sneakyutils.triggers.engine.model

import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.AreaPredicate
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import java.math.BigDecimal
import kotlin.math.floor

/**
 * A deliberately naive implementation of the documented movement semantics (docs/triggers.md, the KDoc of
 * AreaOptions, WatchOptions, AreaCause and EventQueue): every position is checked against every area and every
 * watch, path crossings use exact arithmetic, and nothing is cached or indexed. It shares no code with the engine.
 *
 * Semantics, as documented:
 * - A report on the same block in the same world is dropped (positions are not updated).
 * - A block step on foot has cause MOVE, riding VEHICLE; a teleport, a respawn or a single move longer than 64 blocks
 *   TELEPORT (no path checks). World changes use WORLD_CHANGE, joins JOIN (or silent), registrations REGISTER (or
 *   silent), closes UNREGISTER, quits QUIT, deaths DEATH.
 * - Riders and spectators decide entering only; members stay until their position leaves the area.
 * - A mount, dismount or game-mode change re-checks entering on the spot (no exits, no steps).
 * - Pass-through: a non-teleport move whose path crosses the area's box (for more than a billionth of the move: a
 *   corner or edge touch doesn't count, whatever the rounding), by a player who may
 *   enter and is neither inside before nor after, fires enter then exit.
 * - Steps inside go to announced members who were inside before the step and still are, with the step's cause.
 * - Watches count block steps (world changes and teleports only with includeTeleports; riding only without
 *   onFootOnly); block watches report ENTERED/STAYED/LEFT against the last step they counted.
 * - Order within one step: step watches, then block watches (each in the order added); exits (in the order the
 *   player entered those areas); enters and pass-throughs in registration order, first for the areas reaching the
 *   destination block's chunk, then pass-throughs of the other areas on the path; steps inside (in the order
 *   entered). Calls for several players (a registration, a close) go in join order.
 * - Everything a handler does takes effect at once and its events queue behind the current ones (one FIFO). Enter
 *   and exit are delivered even after an explicit close, never after a silent one; steps, area steps and block events
 *   only while their subscription is active and the player's session (join to quit) goes on.
 * - Dead players are tracked (positions update) but nothing fires for them until they respawn.
 * - Re-entry: a step, block or area-step event is skipped when its own subscription, for the same player, is among
 *   the deliveries whose handlers caused it (directly or through a chain) in the same drain.
 */
internal class ReferenceModel {
    class MP(val id: Int) {
        var online = false
        var session = 0
        var world = -1
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var bx = 0L
        var by = 0L
        var bz = 0L
        var mounted = false
        var spectator = false
        var dead = false

        /** Area id -> silent (no enter was announced, so no exit or steps either). */
        val members = LinkedHashMap<Int, Boolean>()
    }

    class MA(val id: Int, val spec: AreaSpec) {
        var active = true
        var silenced = false
        val pred: AreaPredicate? = spec.predicate()
    }

    class MW(val id: Int, val spec: WatchSpec) {
        var active = true
        var lastMatched = false
    }

    /** A queued event: [cause] is the index in [log] of the delivery whose handler queued it, or -1. */
    private class Q(val ev: Ev, val session: Int, val cause: Int)

    /** One delivery of the current drain: what was delivered and the delivery that caused it. */
    private class Logged(val ev: Ev, val cause: Int)

    val players = Array(Fixed.PLAYERS) { MP(it) }
    val areas = LinkedHashMap<Int, MA>()
    val watches = LinkedHashMap<Int, MW>()
    val delivered = ArrayList<Ev>()
    val ended = ArrayList<Int>()
    val hooks = Hooks { exec(it) }

    private val queue = ArrayDeque<Q>()
    private var draining = false
    private var sessions = 0
    private val log = ArrayList<Logged>()
    private var currentCause = -1

    /** Events skipped by the re-entry rule, for coverage. */
    var selfTriggerDrops = 0L
        private set

    /** A top-level operation: run it, then deliver everything it queued. */
    fun top(op: Op) {
        exec(op)
        drain()
    }

    fun exec(op: Op) {
        when (op) {
            is Op.Join -> join(players[op.p], op)
            is Op.Quit -> quit(players[op.p])
            is Op.Pos -> position(players[op.p], op.w, op.x, op.y, op.z, op.teleport)
            is Op.Death -> death(players[op.p])
            is Op.Respawn -> respawn(players[op.p], op.w, op.x, op.y, op.z)
            is Op.Mount -> setMounted(players[op.p], op.on)
            is Op.Spectate -> setSpectator(players[op.p], op.on)
            is Op.AddArea -> addArea(op.id, op.spec)
            is Op.RemoveArea -> removeArea(op.id, op.silent)
            is Op.AddWatch -> addWatch(op.id, op.spec)
            is Op.RemoveWatch -> watches[op.id]?.active = false
            is Op.AddHook -> hooks.add(op)
        }
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                if (!deliverable(q)) continue
                if (q.ev.kind != Kind.ENTER && q.ev.kind != Kind.EXIT && causedBySelf(q)) {
                    selfTriggerDrops++
                    continue
                }
                log += Logged(q.ev, q.cause)
                delivered += q.ev
                currentCause = log.size - 1
                try {
                    hooks.delivered(q.ev)
                } finally {
                    currentCause = -1
                }
            }
        } finally {
            draining = false
            log.clear()
        }
    }

    private fun causedBySelf(q: Q): Boolean {
        var i = q.cause
        while (i >= 0) {
            val a = log[i].ev
            if (a.kind.isArea == q.ev.kind.isArea && a.subject == q.ev.subject && a.player == q.ev.player) return true
            i = log[i].cause
        }
        return false
    }

    private fun deliverable(q: Q): Boolean {
        val e = q.ev
        val p = players[e.player]
        val live = p.online && p.session == q.session
        return when (e.kind) {
            Kind.ENTER, Kind.EXIT -> !areas[e.subject]!!.silenced
            Kind.AREA_STEP -> areas[e.subject]!!.active && live
            Kind.STEP, Kind.BLOCK -> watches[e.subject]!!.active && live
        }
    }

    private fun emit(kind: Kind, p: MP, subject: Int, detail: String) {
        queue.addLast(Q(Ev(kind, p.id, subject, detail), p.session, currentCause))
    }

    // ---------------------------------------------------------------- rules

    private fun blockOf(v: Double): Long = floor(v).toLong()

    private fun inside(a: MA, p: MP): Boolean =
        a.active && a.spec.world == p.world && a.spec.boxContains(p.bx, p.by, p.bz) &&
            (a.pred == null || a.pred.test(p.x, p.y, p.z))

    private fun touchesChunk(s: AreaSpec, cx: Long, cz: Long): Boolean =
        cx in Math.floorDiv(s.minX, 16)..Math.floorDiv(s.maxX, 16) && cz in Math.floorDiv(s.minZ, 16)..Math.floorDiv(s.maxZ, 16)

    /** Online players in join order. */
    private fun joinOrder(): List<MP> = players.filter { it.online }.sortedBy { it.session }

    private fun mayEnter(a: MA, p: MP): Boolean {
        if (p.spectator && !a.spec.spectators) return false
        return when (a.spec.riders) {
            RiderPolicy.SKIP -> !p.mounted
            RiderPolicy.ALLOW -> true
            RiderPolicy.ONLY -> p.mounted
        }
    }

    private fun setPosition(p: MP, w: Int, x: Double, y: Double, z: Double) {
        p.world = w
        p.x = x
        p.y = y
        p.z = z
        p.bx = blockOf(x)
        p.by = blockOf(y)
        p.bz = blockOf(z)
    }

    private fun position(p: MP, w: Int, x: Double, y: Double, z: Double, teleport: Boolean) {
        if (!p.online || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (w == p.world && blockOf(x) == p.bx && blockOf(y) == p.by && blockOf(z) == p.bz) return
        if (w != p.world) changeWorld(p, w, x, y, z) else stepWithin(p, x, y, z, teleport)
    }

    private fun stepWithin(p: MP, x: Double, y: Double, z: Double, teleport: Boolean) {
        val fx = p.x
        val fy = p.y
        val fz = p.z
        val fbx = p.bx
        val fby = p.by
        val fbz = p.bz
        setPosition(p, p.world, x, y, z)
        if (p.dead) return
        val dx = x - fx
        val dy = y - fy
        val dz = z - fz
        val tp = teleport || dx * dx + dy * dy + dz * dz > 64.0 * 64.0
        val cause = if (tp) AreaCause.TELEPORT else if (p.mounted) AreaCause.VEHICLE else AreaCause.MOVE
        if (fbx != p.bx || fby != p.by || fbz != p.bz) watchEvents(p, p.world, fbx, fby, fbz, tp)

        val before = LinkedHashMap(p.members)
        for ((id, silent) in before) {
            if (inside(areas[id]!!, p)) continue
            p.members.remove(id)
            if (!silent) emit(Kind.EXIT, p, id, cause.name)
        }
        val cx = Math.floorDiv(p.bx, 16L)
        val cz = Math.floorDiv(p.bz, 16L)
        for (nearFirst in listOf(true, false)) {
            for (a in areas.values) {
                if (!a.active || a.spec.world != p.world || before.containsKey(a.id)) continue
                if (touchesChunk(a.spec, cx, cz) != nearFirst) continue
                if (inside(a, p)) {
                    if (!mayEnter(a, p)) continue
                    p.members[a.id] = false
                    emit(Kind.ENTER, p, a.id, cause.name)
                } else if (a.spec.passThrough && !tp && mayEnter(a, p) && crosses(a.spec, fx, fy, fz, x, y, z)) {
                    emit(Kind.ENTER, p, a.id, cause.name)
                    emit(Kind.EXIT, p, a.id, cause.name)
                }
            }
        }
        for ((id, silent) in before) {
            if (!silent && p.members.containsKey(id) && areas[id]!!.spec.stepEvents) emit(Kind.AREA_STEP, p, id, cause.name)
        }
    }

    private fun changeWorld(p: MP, w: Int, x: Double, y: Double, z: Double) {
        val fromWorld = p.world
        val fbx = p.bx
        val fby = p.by
        val fbz = p.bz
        exitAll(p, AreaCause.WORLD_CHANGE)
        endWatches(p) { it.spec.keepOnWorldChange }
        setPosition(p, w, x, y, z)
        if (p.dead) return
        watchEvents(p, fromWorld, fbx, fby, fbz, true)
        enterWhereAllowed(p, AreaCause.WORLD_CHANGE, silent = false)
    }

    private fun enterWhereAllowed(p: MP, cause: AreaCause, silent: Boolean) {
        for (a in areas.values) {
            if (p.members.containsKey(a.id) || !inside(a, p) || !mayEnter(a, p)) continue
            p.members[a.id] = silent
            if (!silent) emit(Kind.ENTER, p, a.id, cause.name)
        }
    }

    private fun exitAll(p: MP, cause: AreaCause) {
        for ((id, silent) in p.members) if (!silent) emit(Kind.EXIT, p, id, cause.name)
        p.members.clear()
    }

    private fun watchEvents(p: MP, fromWorld: Int, fbx: Long, fby: Long, fbz: Long, teleport: Boolean) {
        for (w in watches.values.sortedBy { it.spec.block }) { // step watches first; stable, so in the order added
            if (!w.active || w.spec.p != p.id || !w.spec.accepts(p.mounted, teleport)) continue
            if (!w.spec.block) {
                emit(Kind.STEP, p, w.id, "w$fromWorld $fbx,$fby,$fbz -> w${p.world} ${p.bx},${p.by},${p.bz}" +
                    if (p.mounted) " mounted" else "")
                continue
            }
            var y = p.by
            var type = Fixed.typeAt(p.world, p.bx.toInt(), y.toInt(), p.bz.toInt())
            if (w.spec.offset == BlockOffset.BELOW) {
                // The block just under the feet, or the one below it when that is air.
                y = floor(p.y - 0.001).toLong()
                type = Fixed.typeAt(p.world, p.bx.toInt(), y.toInt(), p.bz.toInt())
                if (Fixed.isAir(type)) {
                    y--
                    type = Fixed.typeAt(p.world, p.bx.toInt(), y.toInt(), p.bz.toInt())
                }
            }
            val matches = type in w.spec.materials
            val was = w.lastMatched
            w.lastMatched = matches
            val match = when {
                matches && was -> BlockMatch.STAYED
                matches -> BlockMatch.ENTERED
                was && w.spec.reportLeft -> BlockMatch.LEFT
                else -> null
            } ?: continue
            emit(Kind.BLOCK, p, w.id, "w${p.world} ${p.bx},$y,${p.bz} $match")
        }
    }

    private fun endWatches(p: MP, keep: (MW) -> Boolean) {
        for (w in watches.values) {
            if (!w.active || w.spec.p != p.id || keep(w)) continue
            w.active = false
            ended += w.id
        }
    }

    private fun join(p: MP, op: Op.Join) {
        if (p.online) {
            // A join without a quit: the quit is its own event (delivered first when not inside a handler).
            quit(p)
            drain()
        }
        p.online = true
        p.session = ++sessions
        p.dead = op.dead
        p.mounted = op.mounted
        p.spectator = op.spectator
        setPosition(p, op.w, op.x, op.y, op.z)
        if (p.dead) return // nothing until the respawn
        for (a in areas.values) {
            if (!inside(a, p) || !mayEnter(a, p)) continue
            val silent = !a.spec.fireOnJoin
            p.members[a.id] = silent
            if (!silent) emit(Kind.ENTER, p, a.id, AreaCause.JOIN.name)
        }
    }

    private fun quit(p: MP) {
        if (!p.online) return
        exitAll(p, AreaCause.QUIT)
        p.online = false
        p.mounted = false
        p.spectator = false
        p.dead = false
        endWatches(p) { it.spec.keepOnQuit }
    }

    private fun death(p: MP) {
        if (!p.online || p.dead) return
        p.dead = true
        exitAll(p, AreaCause.DEATH)
    }

    private fun respawn(p: MP, w: Int, x: Double, y: Double, z: Double) {
        if (!p.online) return
        p.dead = false
        // Always evaluated, even on the block they died on (no same-block drop).
        if (w == p.world) stepWithin(p, x, y, z, teleport = true) else changeWorld(p, w, x, y, z)
    }

    private fun setMounted(p: MP, on: Boolean) {
        if (!p.online || p.mounted == on) return
        p.mounted = on
        if (!p.dead) enterWhereAllowed(p, if (on) AreaCause.VEHICLE else AreaCause.MOVE, silent = false)
    }

    private fun setSpectator(p: MP, on: Boolean) {
        if (!p.online || p.spectator == on) return
        p.spectator = on
        if (!p.dead) enterWhereAllowed(p, if (p.mounted) AreaCause.VEHICLE else AreaCause.MOVE, silent = false)
    }

    private fun addArea(id: Int, spec: AreaSpec) {
        if (areas.containsKey(id)) return
        val a = MA(id, spec)
        areas[id] = a
        for (p in joinOrder()) {
            if (p.dead || !inside(a, p) || !mayEnter(a, p)) continue
            val silent = !spec.fireOnRegister
            p.members[id] = silent
            if (!silent) emit(Kind.ENTER, p, id, AreaCause.REGISTER.name)
        }
    }

    private fun removeArea(id: Int, silent: Boolean) {
        val a = areas[id] ?: return
        if (silent) a.silenced = true
        if (!a.active) return
        a.active = false
        for (p in joinOrder()) {
            val memberSilent = p.members.remove(id) ?: continue
            if (!silent && !memberSilent) emit(Kind.EXIT, p, id, AreaCause.UNREGISTER.name)
        }
    }

    private fun addWatch(id: Int, spec: WatchSpec) {
        if (watches.containsKey(id)) return
        if (!players[spec.p].online && !spec.keepOnQuit) return // the service hands these back already closed
        watches[id] = MW(id, spec)
    }

    companion object {
        /**
         * Whether the move from f to t passes through the area's block box `[min, max + 1)` for a positive length.
         * Exact: doubles convert to BigDecimal without rounding and fractions are compared by cross-multiplying.
         */
        fun crosses(s: AreaSpec, fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double): Boolean {
            // The t range (0..1 along the move) inside the box: (loN / loD, hiN / hiD), denominators positive.
            var loN = BigDecimal.ZERO
            var loD = BigDecimal.ONE
            var hiN = BigDecimal.ONE
            var hiD = BigDecimal.ONE
            val from = doubleArrayOf(fx, fy, fz)
            val to = doubleArrayOf(tx, ty, tz)
            val mins = longArrayOf(s.minX.toLong(), s.minY.toLong(), s.minZ.toLong())
            val maxs = longArrayOf(s.maxX + 1L, s.maxY + 1L, s.maxZ + 1L)
            for (axis in 0..2) {
                val a = BigDecimal(from[axis])
                val d = BigDecimal(to[axis]).subtract(a)
                val lo = BigDecimal.valueOf(mins[axis])
                val hi = BigDecimal.valueOf(maxs[axis])
                if (d.signum() == 0) {
                    if (a < lo || a >= hi) return false
                    continue
                }
                val enterN: BigDecimal
                val exitN: BigDecimal
                val den: BigDecimal
                if (d.signum() > 0) {
                    enterN = lo.subtract(a)
                    exitN = hi.subtract(a)
                    den = d
                } else {
                    enterN = a.subtract(hi)
                    exitN = a.subtract(lo)
                    den = d.negate()
                }
                if (enterN.multiply(loD) > loN.multiply(den)) {
                    loN = enterN
                    loD = den
                }
                if (exitN.multiply(hiD) < hiN.multiply(den)) {
                    hiN = exitN
                    hiD = den
                }
            }
            // Longer than the documented minimum crossing (a billionth of the move): hi - lo > MIN.
            return hiN.multiply(loD).subtract(loN.multiply(hiD)) > MIN_CROSSING.multiply(hiD).multiply(loD)
        }

        private val MIN_CROSSING = BigDecimal("1e-9")
    }
}
