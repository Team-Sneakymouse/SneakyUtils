package com.sneakyutils.triggers.engine.model

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.AreaPredicate
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import java.util.UUID
import kotlin.math.floor

/**
 * The vocabulary shared by the movement engine's differential test: operations, area and watch specs, the events
 * both sides deliver, and the fixed world (players, worlds, block types). See [MovementModelTest].
 */
internal object Fixed {
    val WORLDS = arrayOf(UUID(0L, 0xA1L), UUID(0L, 0xA2L))
    const val PLAYERS = 3

    fun uuid(p: Int): UUID = UUID(1L, p.toLong())
    fun player(uuid: UUID): Int = uuid.leastSignificantBits.toInt()
    fun world(uuid: UUID): Int = WORLDS.indexOf(uuid)

    private val TYPES = arrayOf(
        Material.AIR, Material.AIR, Material.AIR, Material.CAVE_AIR,
        Material.WATER, Material.STONE, Material.SAND, Material.OAK_FENCE,
    )

    /** The block type at a position: a fixed pseudo-random world, the same for both sides. */
    fun typeAt(w: Int, x: Int, y: Int, z: Int): Material {
        var h = w * 0x5DEECE66DL + x * 0x27BB2EE687B0B0FDL + y * 0x2545F4914F6CDD1DL + z * 0x1B873593L
        h = h xor (h ushr 31)
        h *= 0x7FB5D329728EA185L
        h = h xor (h ushr 27)
        return TYPES[(h and 7L).toInt()]
    }

    fun isAir(m: Material) = m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
}

internal enum class Kind { ENTER, EXIT, AREA_STEP, STEP, BLOCK;
    val isArea: Boolean get() = this == ENTER || this == EXIT || this == AREA_STEP
}

/** One delivered handler call: [subject] is the area or watch id; [detail] the cause, the blocks or the match. */
internal data class Ev(val kind: Kind, val player: Int, val subject: Int, val detail: String) {
    override fun toString(): String = "$kind p$player ${if (kind.isArea) "A" else "W"}$subject $detail"
}

/** [MEDDLE]: the whole box, but the engine side's predicate also tries to subscribe and close (always refused). */
internal enum class Shape { BOX, CHECKER, HALF, CIRCLE, MEDDLE }

internal data class AreaSpec(
    val world: Int,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
    val riders: RiderPolicy,
    val spectators: Boolean,
    val passThrough: Boolean,
    val fireOnJoin: Boolean,
    val fireOnRegister: Boolean,
    val stepEvents: Boolean,
    val shape: Shape = Shape.BOX,
    val cx: Double = 0.0,
    val cz: Double = 0.0,
    val r: Double = 0.0,
) {
    fun options(): AreaOptions = AreaOptions.builder().riders(riders).includeSpectators(spectators)
        .passThrough(passThrough).fireOnJoin(fireOnJoin).fireOnRegister(fireOnRegister).stepEvents(stepEvents).build()

    /** The custom shape, a pure function of the position (the documented contract for predicates). */
    fun predicate(): AreaPredicate? = when (shape) {
        Shape.BOX -> null
        Shape.CHECKER -> AreaPredicate { x, _, z -> ((floor(x).toLong() + floor(z).toLong()) and 1L) == 0L }
        Shape.HALF -> AreaPredicate { x, _, _ -> x - floor(x) < 0.5 }
        Shape.CIRCLE -> AreaPredicate { x, _, z -> (x - cx) * (x - cx) + (z - cz) * (z - cz) <= r * r }
        Shape.MEDDLE -> AreaPredicate { _, _, _ -> true }
    }

    fun area(): Area {
        val p = predicate()
        val w = Fixed.WORLDS[world]
        return if (p == null) Area.box(w, minX, minY, minZ, maxX, maxY, maxZ)
        else Area.custom(w, minX, minY, minZ, maxX, maxY, maxZ, p)
    }

    fun boxContains(x: Long, y: Long, z: Long): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    override fun toString(): String =
        "w$world [$minX,$minY,$minZ]..[$maxX,$maxY,$maxZ] $riders" +
            (if (spectators) " spect" else "") + (if (passThrough) " pass" else "") +
            (if (!fireOnJoin) " silentJoin" else "") + (if (fireOnRegister) " announceRegister" else "") +
            (if (stepEvents) " steps" else "") + (if (shape == Shape.BOX) "" else " $shape") +
            (if (shape == Shape.CIRCLE) "($cx,$cz,r=$r)" else "")
}

internal data class WatchSpec(
    val p: Int,
    val block: Boolean,
    val offset: BlockOffset,
    val materials: Set<Material>,
    val reportLeft: Boolean,
    val onFootOnly: Boolean,
    val keepOnQuit: Boolean,
    val keepOnWorldChange: Boolean,
    val includeTeleports: Boolean,
) {
    fun options(): WatchOptions = WatchOptions.builder().onFootOnly(onFootOnly).keepOnQuit(keepOnQuit)
        .keepOnWorldChange(keepOnWorldChange).includeTeleports(includeTeleports).build()

    fun accepts(mounted: Boolean, teleport: Boolean) = !(mounted && onFootOnly) && !(teleport && !includeTeleports)

    override fun toString(): String =
        "p$p " + (if (block) "block $offset $materials${if (reportLeft) "" else " noLeft"}" else "steps") +
            (if (onFootOnly) "" else " riders") + (if (keepOnQuit) " keepOnQuit" else "") +
            (if (keepOnWorldChange) "" else " endOnWorldChange") + (if (includeTeleports) " teleports" else "")
}

/** Which delivered event fires a hook: any field -1 / null matches everything. */
internal data class Trigger(val kind: Kind?, val player: Int, val subject: Int) {
    fun matches(e: Ev) = (kind == null || kind == e.kind) && (player < 0 || player == e.player) &&
        (subject < 0 || (subject == e.subject && (kind == null || kind.isArea == e.kind.isArea)))
}

/**
 * One operation of a test sequence. Coordinates are absolute, so a sequence replays (and shrinks) without depending on
 * where earlier operations left a player. Operations that don't apply when they run (a move for an offline player, a
 * second respawn) do nothing on both sides.
 */
internal sealed class Op {
    data class Join(val p: Int, val w: Int, val x: Double, val y: Double, val z: Double, val mounted: Boolean, val spectator: Boolean,
                    val dead: Boolean = false) : Op()
    data class Quit(val p: Int) : Op()

    /** A position report: a move event or the mounted poll ([teleport] false), or a teleport; any world. */
    data class Pos(val p: Int, val w: Int, val x: Double, val y: Double, val z: Double, val teleport: Boolean) : Op()
    data class Death(val p: Int) : Op()
    data class Respawn(val p: Int, val w: Int, val x: Double, val y: Double, val z: Double) : Op()
    data class Mount(val p: Int, val on: Boolean) : Op()
    data class Spectate(val p: Int, val on: Boolean) : Op()
    data class AddArea(val id: Int, val spec: AreaSpec) : Op()
    data class RemoveArea(val id: Int, val silent: Boolean) : Op()
    data class AddWatch(val id: Int, val spec: WatchSpec) : Op()
    data class RemoveWatch(val id: Int) : Op()

    /** From now on, the first delivered event matching [trigger] runs [action] inside the handler, once. */
    data class AddHook(val id: Int, val trigger: Trigger, val action: Op) : Op()

    override fun toString(): String = when (this) {
        is Join -> "join p$p w$w ${xyz(x, y, z)}${if (mounted) " mounted" else ""}${if (spectator) " spectator" else ""}${if (dead) " dead" else ""}"
        is Quit -> "quit p$p"
        is Pos -> "${if (teleport) "teleport" else "move"} p$p w$w ${xyz(x, y, z)}"
        is Death -> "death p$p"
        is Respawn -> "respawn p$p w$w ${xyz(x, y, z)}"
        is Mount -> "${if (on) "mount" else "dismount"} p$p"
        is Spectate -> "spectator ${if (on) "on" else "off"} p$p"
        is AddArea -> "add A$id $spec"
        is RemoveArea -> "${if (silent) "silence" else "close"} A$id"
        is AddWatch -> "watch W$id $spec"
        is RemoveWatch -> "unwatch W$id"
        is AddHook -> "hook H$id on $trigger: $action"
    }

    companion object {
        fun xyz(x: Double, y: Double, z: Double) = "($x, $y, $z)"
    }
}

/** Runs each hook at most once, on the first delivered event that matches it. Each side has its own. */
internal class Hooks(private val run: (Op) -> Unit) {
    private class H(val trigger: Trigger, val action: Op) {
        var fired = false
    }

    private val list = ArrayList<H>()

    var firedCount = 0L
        private set

    fun add(op: Op.AddHook) {
        list += H(op.trigger, op.action)
    }

    fun delivered(e: Ev) {
        for (h in list) {
            if (h.fired || !h.trigger.matches(e)) continue
            h.fired = true
            firedCount++
            run(h.action)
            return
        }
    }
}
