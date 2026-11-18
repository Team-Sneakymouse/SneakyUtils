package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.Occupancy
import com.sneakyutils.triggers.OccupancyCause
import com.sneakyutils.triggers.OccupancyHandler
import com.sneakyutils.triggers.engine.AreaReg
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Collections
import java.util.UUID

/**
 * [Occupancy]: an area registration in the engine (with [AreaReg.occupancy] set) plus the members as delivered.
 *
 * The engine decides membership when it evaluates; this keeps the players whose enter has been delivered and whose
 * exit hasn't, so [count] and the first-in / last-out calls follow the calls the handler got. Enters and exits always
 * reach [delivered] in pairs (also after an explicit close, and also when the owner has disabled meanwhile: then the
 * set is updated but no handler is called), so the set empties on its own; a silent close (owner or SneakyUtils
 * disable) drops the queued ones and clears it instead.
 */
internal class OccupancySub(
    service: TriggerService,
    owner: Plugin,
    @JvmField val reg: AreaReg,
    @JvmField val handler: OccupancyHandler,
) : SubBase(service, owner, KIND, reg.options.label), Occupancy {
    init {
        reg.link = this
    }

    /** Members by UUID, in the order they entered. */
    private val members = LinkedHashMap<UUID, Player>()

    override val area: Area get() = reg.area

    override val count: Int get() = members.size

    override val isOccupied: Boolean get() = members.isNotEmpty()

    override val players: List<Player> get() = Collections.unmodifiableList(ArrayList(members.values))

    override fun contains(player: Player): Boolean = members.containsKey(player.uniqueId)

    override fun moveTo(area: Area) {
        service.requireMainThread()
        check(!closed) { "occupancy $id is closed" }
        service.core.checkMutable("move an occupancy")
        service.refreshTiming()
        service.stage(TriggerService.STAGE_INDEX, "area-index.move") {
            // A shape is tested at exact positions, which the engine stores only on block steps: refresh them first.
            if (area.isCustom) service.refinePositions(area)
            service.core.moveArea(reg, area)
        }
    }

    /** One delivered enter or exit (from [TriggerService.occupancy]). */
    fun delivered(p: Player, entered: Boolean, cause: OccupancyCause) {
        val id = p.uniqueId
        if (entered) {
            if (members.putIfAbsent(id, p) != null) return
            val first = members.size == 1
            if (first && mayCall()) service.guard(this, CB_FIRST_IN) { handler.onFirstIn(this, p, cause) }
            // Checked again: onFirstIn may have disabled the owner (a silent close). An explicit close from it still
            // gets the enter, so enter and exit keep pairing up.
            if (mayCall()) service.guard(this, CB_ENTER) { handler.onEnter(this, p, cause) }
        } else {
            if (members.remove(id) == null) return
            val last = members.isEmpty()
            if (mayCall()) service.guard(this, CB_EXIT) { handler.onExit(this, p, cause) }
            if (last && mayCall()) service.guard(this, CB_LAST_OUT) { handler.onLastOut(this, p, cause) }
        }
    }

    /** Handlers are called only while the owner and SneakyUtils are enabled and the occupancy wasn't closed silently. */
    private fun mayCall(): Boolean = owner.isEnabled && !reg.silenced && service.running

    /**
     * Makes the members match the engine's membership without calling anything: after the queue dropped events of
     * this occupancy (a handler loop over the per-drain limit), the enters and exits it dropped would otherwise leave
     * players counted who left, or uncounted who are inside. Keeps the entry order of players who stay; players the
     * engine has but the set hasn't are added in join order.
     */
    fun reconcile() {
        if (reg.silenced) {
            members.clear()
            return
        }
        val core = service.core
        val kept = LinkedHashMap<UUID, Player>()
        for ((id, p) in members) {
            val s = core.state(id) ?: continue
            if (s.isMember(reg)) kept[id] = s.player ?: p
        }
        for (s in core.liveStates) {
            if (kept.containsKey(s.uuid) || !s.isMember(reg)) continue
            val p = s.player ?: continue
            kept[s.uuid] = p
        }
        members.clear()
        members.putAll(kept)
    }

    override fun callbackName(cb: Int): String = when (cb) {
        CB_ENTER -> "occupancy-enter"
        CB_EXIT -> "occupancy-exit"
        CB_FIRST_IN -> "occupancy-first-in"
        CB_LAST_OUT -> "occupancy-last-out"
        CB_PREDICATE -> "occupancy-predicate"
        else -> KIND
    }

    override fun checkRelease() = service.core.checkMutable("close an occupancy")

    override fun release(silent: Boolean) {
        service.stage(TriggerService.STAGE_INDEX, "area-index.remove") { service.core.removeArea(reg, silent) }
        // An explicit close from a handler leaves the exits queued behind it; should SneakyUtils shut down before
        // they are delivered, the service empties every handle (TriggerService.shutdown).
        if (silent) members.clear()
    }

    /** SneakyUtils is shutting down: nothing more is delivered, so the handle holds nobody. */
    fun clearMembers() = members.clear()

    companion object {
        const val KIND = "occupancy"
    }
}
