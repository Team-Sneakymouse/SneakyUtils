package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.BlockWatchHandler
import com.sneakyutils.triggers.StepHandler
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

/**
 * The movement engine's record of one online player. Created on join, dropped on quit. Fields are plain and public
 * to the engine: the hot path reads them directly. Only [MovementCore] writes them.
 */
internal class PlayerState(@JvmField val uuid: UUID) {
    /** The Bukkit player handlers receive; null in pure engine tests. */
    @JvmField var player: Player? = null

    /** Set when the player quit; events still queued for them deliver enters and exits only. */
    @JvmField var removed: Boolean = false

    // Last reported position: world, block, exact, and the block's chunk.
    @JvmField var worldId: UUID? = null
    @JvmField var bx: Int = 0
    @JvmField var by: Int = 0
    @JvmField var bz: Int = 0
    @JvmField var px: Double = 0.0
    @JvmField var py: Double = 0.0
    @JvmField var pz: Double = 0.0
    @JvmField var chunkKey: Long = 0

    /** The world's area index (null when the world has no areas) and the cached chunk's areas. */
    @JvmField var worldAreas: WorldAreas? = null
    @JvmField var chunkAreas: Array<AreaReg>? = null

    /** [AreaIndex.version] when [worldAreas] and [chunkAreas] were read; a different version means re-read. */
    @JvmField var indexVersion: Long = -1

    /**
     * Areas the player is inside: `members[0 until memberCount]`. [memberSilent] marks a stay that announced no enter
     * (and so gets no exit or steps); [memberSince] is the evaluation stamp of the enter, so the entering step
     * doesn't also count as a step inside.
     */
    @JvmField var members: Array<AreaReg?> = EMPTY_MEMBERS
    @JvmField var memberSilent: BooleanArray = EMPTY_FLAGS
    @JvmField var memberSince: LongArray = EMPTY_STAMPS
    @JvmField var memberCount: Int = 0

    /** Step and block watches; null when the player has none (the hot path tests only this). */
    @JvmField var watches: PlayerWatches? = null

    @JvmField var mounted: Boolean = false

    /** Index in the engine's mounted list, or -1. */
    @JvmField var mountedIndex: Int = -1

    @JvmField var spectator: Boolean = false
    @JvmField var dead: Boolean = false

    /** A [BlockKey] for the current block, made only when a step watch needs one; null until then. */
    @JvmField var keyObj: BlockKey? = null

    fun isMember(reg: AreaReg): Boolean {
        for (i in 0 until memberCount) if (members[i] === reg) return true
        return false
    }

    override fun toString(): String = "PlayerState($uuid @ $worldId $bx,$by,$bz, members=$memberCount)"

    companion object {
        private val EMPTY_MEMBERS = arrayOfNulls<AreaReg>(0)
        private val EMPTY_FLAGS = BooleanArray(0)
        private val EMPTY_STAMPS = LongArray(0)
    }
}

/** A player's step and block watches. Arrays are replaced on change (copy-on-write). */
internal class PlayerWatches(@JvmField val uuid: UUID) {
    @JvmField var steps: Array<StepWatch> = NO_STEPS
    @JvmField var blocks: Array<BlockWatch> = NO_BLOCKS

    val isEmpty: Boolean get() = steps.isEmpty() && blocks.isEmpty()

    companion object {
        val NO_STEPS = arrayOf<StepWatch>()
        val NO_BLOCKS = arrayOf<BlockWatch>()
    }
}

/** Shared by the watch kinds: the player, options, the subscription link and the active flag. */
internal abstract class Watch(@JvmField val playerId: UUID, @JvmField val options: WatchOptions) {
    @JvmField var link: Any? = null
    @JvmField var active: Boolean = false

    /** Whether the watch applies to a step with this riding state and kind. */
    fun accepts(mounted: Boolean, teleport: Boolean): Boolean =
        !(mounted && options.onFootOnly) && !(teleport && !options.includeTeleports)
}

internal class StepWatch(playerId: UUID, @JvmField val handler: StepHandler?, options: WatchOptions) :
    Watch(playerId, options)

/**
 * A block watch (feet block or block under). [reportLeft] is false for `watchBlockUnder`, which only reports matches.
 * [lastMatched] is whether the block matched at the last step the watch counted.
 */
internal class BlockWatch(
    playerId: UUID,
    @JvmField val offset: BlockOffset,
    materials: Set<Material>,
    @JvmField val handler: BlockWatchHandler?,
    @JvmField val reportLeft: Boolean,
    options: WatchOptions,
) : Watch(playerId, options) {
    /** An EnumSet copy: `contains` is a bit test, no allocation. */
    @JvmField val materials: EnumSet<Material> =
        if (materials.isEmpty()) EnumSet.noneOf(Material::class.java) else EnumSet.copyOf(materials)

    @JvmField var lastMatched: Boolean = false
}
