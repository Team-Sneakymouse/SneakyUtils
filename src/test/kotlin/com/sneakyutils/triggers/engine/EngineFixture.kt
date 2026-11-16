package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaCause
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockMatch
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import java.util.UUID

/**
 * Drives [MovementCore] without a server: records what the engine delivers as readable lines, and lets a test hook
 * any delivery to re-enter the engine (teleport, quit, subscribe) the way a handler would.
 */
internal class RecordingSink : EngineSink {
    val log = ArrayList<String>()
    val names = HashMap<Any, String>()
    var hook: ((String) -> Unit)? = null
    val blocks = HashMap<Triple<Int, Int, Int>, Material>()
    var predicateFailures = 0
    var dropped = 0
    val ended = ArrayList<Watch>()

    private fun record(line: String) {
        log += line
        hook?.invoke(line)
    }

    private fun n(o: Any) = names[o] ?: o.toString()
    private fun n(s: PlayerState) = names[s.uuid] ?: s.toString()

    override fun enter(state: PlayerState, reg: AreaReg, cause: AreaCause) = record("enter ${n(reg)} ${n(state)} $cause")
    override fun exit(state: PlayerState, reg: AreaReg, cause: AreaCause) = record("exit ${n(reg)} ${n(state)} $cause")
    override fun areaStep(state: PlayerState, reg: AreaReg, cause: AreaCause) = record("inside ${n(reg)} ${n(state)} $cause")
    override fun step(state: PlayerState, watch: StepWatch, from: BlockKey, to: BlockKey, mounted: Boolean) =
        record("step ${n(watch)} ${n(state)} ${from.x},${from.y},${from.z}->${to.x},${to.y},${to.z}${if (mounted) " mounted" else ""}")

    override fun blockWatch(state: PlayerState, watch: BlockWatch, worldId: UUID, x: Int, y: Int, z: Int, match: BlockMatch) =
        record("block ${n(watch)} ${n(state)} $x,$y,$z $match")

    override fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material = blocks[Triple(x, y, z)] ?: Material.AIR

    override fun predicateFailed(reg: AreaReg, error: Throwable) {
        predicateFailures++
    }

    override fun watchEnded(watch: Watch) {
        ended += watch
    }

    override fun eventsDropped(count: Int, lastSubject: Any?) {
        dropped += count
    }

    fun take(): List<String> = ArrayList(log).also { log.clear() }
}

internal class EngineFixture(maxEvents: Int = EventQueue.DEFAULT_MAX_PER_DRAIN) {
    val world: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val nether: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    val sink = RecordingSink()
    val index = AreaIndex()
    val core = MovementCore(index, sink, MovementCore.DEFAULT_MAX_SEGMENT_BLOCKS, maxEvents)

    fun area(name: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, options: AreaOptions = AreaOptions.DEFAULT, w: UUID = world): AreaReg =
        add(name, Area.box(w, x1, y1, z1, x2, y2, z2), options)

    fun add(name: String, area: Area, options: AreaOptions = AreaOptions.DEFAULT): AreaReg {
        val reg = AreaReg(area, options)
        sink.names[reg] = name
        core.addArea(reg)
        return reg
    }

    fun join(name: String, x: Double, y: Double, z: Double, w: UUID = world, mounted: Boolean = false): PlayerState {
        val id = UUID.nameUUIDFromBytes(name.toByteArray())
        sink.names[id] = name
        return core.join(id, null, w, x, y, z, mounted, false)
    }

    fun move(s: PlayerState, x: Double, y: Double, z: Double, w: UUID = world) = core.position(s, w, x, y, z, false)
    fun teleport(s: PlayerState, x: Double, y: Double, z: Double, w: UUID = world) = core.position(s, w, x, y, z, true)

    fun stepWatch(name: String, s: PlayerState, options: WatchOptions = WatchOptions.DEFAULT): StepWatch {
        val w = StepWatch(s.uuid, null, options)
        sink.names[w] = name
        core.addWatch(w)
        return w
    }

    fun blockWatch(name: String, s: PlayerState, at: BlockOffset, vararg materials: Material, reportLeft: Boolean = true,
                   options: WatchOptions = WatchOptions.DEFAULT): BlockWatch {
        val w = BlockWatch(s.uuid, at, materials.toSet(), null, reportLeft, options)
        sink.names[w] = name
        core.addWatch(w)
        return w
    }
}
