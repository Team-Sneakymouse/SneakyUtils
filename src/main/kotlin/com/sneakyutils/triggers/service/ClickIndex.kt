package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.BlockBreakHandler
import com.sneakyutils.triggers.BlockClickHandler
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BreakOptions
import com.sneakyutils.triggers.ClickOptions
import com.sneakyutils.triggers.ClickSide
import com.sneakyutils.triggers.EntityClickHandler
import com.sneakyutils.triggers.EntityMatch
import com.sneakyutils.triggers.engine.LongObjectMap
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A block click or break subscription, by position ([key]) or by type ([material]). */
internal class BlockSub(
    service: TriggerService,
    owner: Plugin,
    kind: String,
    @JvmField val key: BlockKey?,
    @JvmField val material: Material?,
    @JvmField val click: BlockClickHandler?,
    @JvmField val brk: BlockBreakHandler?,
    @JvmField val options: ClickOptions,
    @JvmField val breakOptions: BreakOptions = BreakOptions.DEFAULT,
) : SubBase(service, owner, kind) {
    override fun release(silent: Boolean) = service.clicks.remove(this)

    companion object {
        const val CLICK = "block-click"
        const val CLICK_TYPE = "block-type"
        const val BREAK = "block-break"
        const val BREAK_TYPE = "block-break-type"
    }
}

internal class EntitySub(
    service: TriggerService,
    owner: Plugin,
    @JvmField val match: EntityMatch,
    @JvmField val handler: EntityClickHandler,
    @JvmField val options: ClickOptions,
) : SubBase(service, owner, KIND) {
    override fun release(silent: Boolean) = service.clicks.remove(this)

    companion object {
        const val KIND = "entity-click"
    }
}

/**
 * Block and entity click indexes. Every lookup is a field check, an array index or one primitive map lookup; a click
 * on a block or entity nobody subscribed to costs that and nothing more. Arrays are copy-on-write: dispatch iterates
 * a snapshot, and subscriptions closed meanwhile are skipped.
 *
 * - Block positions: `world UUID -> LongObjectMap(BlockKey.pack -> BlockSub[])`, separately for clicks and breaks.
 * - Block types: `BlockSub[]` by `Material.ordinal`, separately for clicks and breaks.
 * - Entities: `EntitySub[]` by `EntityType.ordinal`, plus one short "any type" list for matches without a type.
 */
internal class ClickIndex(private val service: TriggerService) {
    private val clickPos = HashMap<UUID, LongObjectMap<Array<BlockSub>>>()
    private val breakPos = HashMap<UUID, LongObjectMap<Array<BlockSub>>>()
    private val clickType = arrayOfNulls<Array<BlockSub>>(MATERIAL_COUNT)
    private val breakType = arrayOfNulls<Array<BlockSub>>(MATERIAL_COUNT)
    private val entityType = arrayOfNulls<Array<EntitySub>>(ENTITY_TYPE_COUNT)
    private var entityAny = NO_ENTITY_SUBS

    // Subscriptions per family: the listeners return at once when theirs is 0.
    var clickSubs = 0; private set
    var breakSubs = 0; private set
    var entitySubs = 0; private set

    // Always-on counters.
    @JvmField var interactEvents = 0L
    @JvmField var breakEvents = 0L
    @JvmField var entityEvents = 0L

    // ---------------------------------------------------------------- entity right-click pairing
    // A right click on an entity may arrive as an "interact at" event followed by a plain one for the same player,
    // entity and hand in the same tick; it is one click. Each player has one small slot per hand (made on their first
    // "at" click, reused after, dropped on quit), so clicks of other players or of the other hand in between can't
    // disturb a pair. A slot holds the entity, the tick and the cancel decision, and is used up by its plain event.

    private class AtSlots {
        @JvmField val entity = intArrayOf(NO_ENTITY, NO_ENTITY)
        @JvmField val tick = IntArray(2)
        @JvmField val cancelled = BooleanArray(2)

        // The last main-hand right click a subscription cancelled (see "a click's off-hand half"): tick and target.
        @JvmField var cancelTick = -1
        @JvmField var cancelEntity = NO_ENTITY
        @JvmField var cancelWorld: UUID? = null
        @JvmField var cancelX = 0
        @JvmField var cancelY = 0
        @JvmField var cancelZ = 0
    }

    private val atSlots = HashMap<UUID, AtSlots>()

    /** Players holding a pairing slot, for tests. */
    internal val pairingRecords: Int get() = atSlots.size

    /**
     * The tick of the latest remembered decision (an "interact at" click, or a cancelled main-hand click). The
     * remembered halves must be applied whatever is subscribed by the time the second half comes (a handler may have
     * closed its subscription or changed the block), so the listeners' "nothing subscribed" shortcut also checks
     * this: one int compare, and the tick is read only after a decision was ever remembered.
     */
    private var decisionTick = Int.MIN_VALUE

    /** Whether a decision remembered this tick may apply to the event being handled. */
    fun decisionPending(): Boolean = decisionTick != Int.MIN_VALUE && decisionTick == tick()

    /** Remembers an "interact at" click and whether it was cancelled. */
    fun rememberAt(player: UUID, entityId: Int, hand: EquipmentSlot?, tick: Int, cancelled: Boolean) {
        decisionTick = tick
        val slots = atSlots.getOrPut(player) { AtSlots() }
        val i = slotOf(hand)
        slots.entity[i] = entityId
        slots.tick[i] = tick
        slots.cancelled[i] = cancelled
    }

    /**
     * Uses up the pairing for a plain click: [PAIRED_CANCELLED] or [PAIRED] when it is the second half of a
     * remembered "at" click this tick, [UNPAIRED] otherwise.
     */
    fun consumeAt(player: UUID, entityId: Int, hand: EquipmentSlot?, tick: Int): Int {
        val slots = atSlots[player] ?: return UNPAIRED
        val i = slotOf(hand)
        if (slots.entity[i] != entityId || slots.tick[i] != tick) return UNPAIRED
        slots.entity[i] = NO_ENTITY
        return if (slots.cancelled[i]) PAIRED_CANCELLED else PAIRED
    }

    /** The player quit: drop their pairing slots. */
    fun forget(player: UUID) {
        atSlots.remove(player)
    }

    private fun slotOf(hand: EquipmentSlot?): Int = if (hand == EquipmentSlot.OFF_HAND) 1 else 0

    // ---------------------------------------------------------------- a click's off-hand half
    // Paper fires a right click once per hand: the main hand, then the off hand when the main hand did nothing with
    // the target (an empty main hand, for example: a vanilla client then uses the off-hand item, placing a block
    // against it or a bucket on a cow; measured on Paper 26.2). When a subscription cancels the main-hand half, the
    // off-hand half of the same click (same player, target and tick) is cancelled too, so the click does nothing.

    private fun tick(): Int = service.plugin.server.currentTick

    private fun rememberCancelled(player: UUID, entityId: Int, world: UUID?, x: Int, y: Int, z: Int) {
        val slots = atSlots.getOrPut(player) { AtSlots() }
        val now = tick()
        decisionTick = now
        slots.cancelTick = now
        slots.cancelEntity = entityId
        slots.cancelWorld = world
        slots.cancelX = x
        slots.cancelY = y
        slots.cancelZ = z
    }

    /** A newer main-hand half that wasn't cancelled replaces the remembered one (no allocation). */
    private fun forgetCancelled(player: UUID) {
        atSlots[player]?.cancelTick = -1
    }

    private fun mainHalfCancelled(player: UUID, entityId: Int, world: UUID?, x: Int, y: Int, z: Int): Boolean {
        val slots = atSlots[player] ?: return false
        return slots.cancelTick == tick() && slots.cancelEntity == entityId && slots.cancelWorld == world &&
            slots.cancelX == x && slots.cancelY == y && slots.cancelZ == z
    }

    // ---------------------------------------------------------------- registration

    fun add(sub: BlockSub) {
        val isClick = sub.click != null
        if (sub.key != null) {
            val maps = if (isClick) clickPos else breakPos
            val map = maps.getOrPut(sub.key.worldId) { LongObjectMap() }
            val k = sub.key.asLong()
            map.put(k, append(map[k], sub))
        } else {
            val arr = if (isClick) clickType else breakType
            val i = sub.material!!.ordinal
            arr[i] = append(arr[i], sub)
        }
        if (isClick) clickSubs++ else breakSubs++
    }

    fun add(sub: EntitySub) {
        val t = sub.match.type
        if (t == null) entityAny = entityAny + sub else entityType[t.ordinal] = append(entityType[t.ordinal], sub)
        entitySubs++
    }

    fun remove(sub: BlockSub) {
        val isClick = sub.click != null
        if (sub.key != null) {
            val maps = if (isClick) clickPos else breakPos
            val map = maps[sub.key.worldId] ?: return
            val k = sub.key.asLong()
            val old = map[k] ?: return
            val left = without(old, sub)
            if (left === old) return
            if (left == null) map.remove(k) else map.put(k, left)
            if (map.isEmpty) maps.remove(sub.key.worldId)
        } else {
            val arr = if (isClick) clickType else breakType
            val i = sub.material!!.ordinal
            val old = arr[i] ?: return
            val left = without(old, sub)
            if (left === old) return
            arr[i] = left
        }
        if (isClick) clickSubs-- else breakSubs--
    }

    fun remove(sub: EntitySub) {
        val t = sub.match.type
        if (t == null) {
            val left = without(entityAny, sub)
            if (left === entityAny) return
            entityAny = left ?: NO_ENTITY_SUBS
        } else {
            val old = entityType[t.ordinal] ?: return
            val left = without(old, sub)
            if (left === old) return
            entityType[t.ordinal] = left
        }
        entitySubs--
    }

    /**
     * The block position, block type or entity type with the most subscriptions, described for `/triggers` (no
     * player data), and the count; null when there are none. Every click there runs through all of them. Scans.
     */
    fun densest(): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        fun offer(n: Int, what: () -> String) {
            if (n > (best?.second ?: 0)) best = what() to n
        }
        for ((label, maps) in listOf("click" to clickPos, "break" to breakPos)) {
            for ((world, map) in maps) {
                map.forEachEntry { k, subs ->
                    offer(subs.size) {
                        "$label block ${BlockKey.unpackX(k)},${BlockKey.unpackY(k)},${BlockKey.unpackZ(k)} in " +
                            (service.plugin.server.getWorld(world)?.name ?: world.toString())
                    }
                }
            }
        }
        for ((label, arr) in listOf("click" to clickType, "break" to breakType)) {
            for (i in arr.indices) {
                val subs = arr[i] ?: continue
                offer(subs.size) { "$label type ${Material.entries[i]}" }
            }
        }
        for (i in entityType.indices) {
            val subs = entityType[i] ?: continue
            offer(subs.size) { "entity type ${EntityType.entries[i]}" }
        }
        offer(entityAny.size) { "entity matches without a type" }
        return best
    }

    /** Position subscriptions in one world, for world unload. Allocates. */
    fun blockSubsInWorld(worldId: UUID): List<BlockSub> {
        val out = ArrayList<BlockSub>()
        clickPos[worldId]?.forEachEntry { _, subs -> out.addAll(subs) }
        breakPos[worldId]?.forEachEntry { _, subs -> out.addAll(subs) }
        return out
    }

    // ---------------------------------------------------------------- dispatch

    /** A player clicked [block]. Cheapest rejection first: nothing subscribed, then the position and type lookups. */
    fun interact(e: PlayerInteractEvent, block: Block, side: ClickSide) {
        val atPos = lookup(clickPos, block)
        val ofType = clickType[block.type.ordinal]
        if (atPos == null && ofType == null && !decisionPending()) return
        val player = e.player
        var cancelled = false
        if (atPos != null || ofType != null) {
            service.refreshTiming()
            if (atPos != null) cancelled = dispatchClick(atPos, e, player, block, side)
            if (ofType != null) cancelled = dispatchClick(ofType, e, player, block, side) || cancelled
        }
        if (side != ClickSide.RIGHT) return
        // After the subscriptions (both-hands ones still get the off-hand event as it came), and whatever is
        // subscribed or at the block now: the main-hand half's handler may have closed its subscription or changed
        // the block's type.
        val worldId = block.world.uid
        if (e.hand == EquipmentSlot.HAND) {
            // A player's off-hand half belongs to their latest main-hand half.
            if (cancelled) rememberCancelled(player.uniqueId, NO_ENTITY, worldId, block.x, block.y, block.z) else forgetCancelled(player.uniqueId)
        } else if (e.hand == EquipmentSlot.OFF_HAND && mainHalfCancelled(player.uniqueId, NO_ENTITY, worldId, block.x, block.y, block.z)) {
            e.setUseInteractedBlock(Event.Result.DENY)
            e.setUseItemInHand(Event.Result.DENY)
        }
    }

    /** Returns whether a handler claimed the click and cancelled it. */
    private fun dispatchClick(subs: Array<BlockSub>, e: PlayerInteractEvent, player: Player, block: Block, side: ClickSide): Boolean {
        val hand = e.hand
        var cancelled = false
        for (sub in subs) {
            if (sub.closed) continue
            val o = sub.options
            if (!o.sides.accepts(side)) continue
            if (side != ClickSide.PHYSICAL && !handAccepted(o, hand)) continue
            if (o.ignoreCancelled && e.useInteractedBlock() == Event.Result.DENY) continue
            val handler = sub.click ?: continue
            var claimed = false
            service.guard(sub) { claimed = handler.onClick(player, block, side, e) }
            if (claimed && o.cancelEvent) {
                e.setUseInteractedBlock(Event.Result.DENY)
                e.setUseItemInHand(Event.Result.DENY)
                cancelled = true
            }
        }
        return cancelled
    }

    /** A player broke [block]; the event may be cancelled already (each subscription decides, see [BreakOptions]). */
    fun broke(e: BlockBreakEvent) {
        val block = e.block
        val atPos = lookup(breakPos, block)
        val ofType = breakType[block.type.ordinal]
        if (atPos == null && ofType == null) return
        service.refreshTiming()
        val player = e.player
        if (atPos != null) dispatchBreak(atPos, e, player, block)
        if (ofType != null) dispatchBreak(ofType, e, player, block)
    }

    private fun dispatchBreak(subs: Array<BlockSub>, e: BlockBreakEvent, player: Player, block: Block) {
        for (sub in subs) {
            if (sub.closed) continue
            // Cancelled by another plugin or an earlier handler: only subscriptions that asked see it.
            if (e.isCancelled && sub.breakOptions.ignoreCancelled) continue
            val handler = sub.brk ?: continue
            service.guard(sub) { handler.onBreak(player, block, e) }
        }
    }

    /**
     * A player clicked [entity]. The type list first, then the any-type list; tag, PDC and Citizens tests run only for
     * subscriptions in those lists. Returns whether a handler claimed the click and cancelled the event.
     */
    fun <E> entity(e: E, player: Player, entity: Entity, side: ClickSide, hand: EquipmentSlot?): Boolean
        where E : PlayerEvent, E : Cancellable {
        val typed = entityType[entity.type.ordinal]
        val any = entityAny
        if (typed == null && any.isEmpty() && !decisionPending()) return false
        var cancelled = false
        if (typed != null || any.isNotEmpty()) {
            service.refreshTiming()
            if (typed != null) cancelled = dispatchEntity(typed, e, player, entity, side, hand)
            if (any.isNotEmpty()) cancelled = dispatchEntity(any, e, player, entity, side, hand) || cancelled
        }
        if (side != ClickSide.RIGHT) return cancelled
        // After the subscriptions (both-hands ones still get the off-hand event as it came), and whatever is
        // subscribed now (the main-hand half's handler may have closed its subscription).
        if (hand == EquipmentSlot.HAND) {
            if (cancelled) rememberCancelled(player.uniqueId, entity.entityId, null, 0, 0, 0) else forgetCancelled(player.uniqueId)
        } else if (hand == EquipmentSlot.OFF_HAND && mainHalfCancelled(player.uniqueId, entity.entityId, null, 0, 0, 0)) {
            e.isCancelled = true
            cancelled = true
        }
        return cancelled
    }

    private fun <E> dispatchEntity(subs: Array<EntitySub>, e: E, player: Player, entity: Entity, side: ClickSide, hand: EquipmentSlot?): Boolean
        where E : PlayerEvent, E : Cancellable {
        var cancelled = false
        for (sub in subs) {
            if (sub.closed) continue
            val o = sub.options
            if (!o.sides.accepts(side) || !handAccepted(o, hand)) continue
            if (o.ignoreCancelled && e.isCancelled) continue
            var claimed = false
            service.guard(sub) {
                if (sub.match.matches(entity)) claimed = sub.handler.onClick(player, entity, side, e)
            }
            if (claimed && o.cancelEvent) {
                e.isCancelled = true
                cancelled = true
            }
        }
        return cancelled
    }

    private fun lookup(maps: HashMap<UUID, LongObjectMap<Array<BlockSub>>>, block: Block): Array<BlockSub>? {
        if (maps.isEmpty()) return null
        val map = maps[block.world.uid] ?: return null
        val x = block.x
        val y = block.y
        val z = block.z
        if (!BlockKey.isPackable(x, y, z)) return null
        return map[BlockKey.pack(x, y, z)]
    }

    private fun handAccepted(o: ClickOptions, hand: EquipmentSlot?): Boolean = when (hand) {
        EquipmentSlot.HAND -> o.mainHand
        EquipmentSlot.OFF_HAND -> o.offHand
        else -> true
    }

    companion object {
        const val UNPAIRED = 0
        const val PAIRED = 1
        const val PAIRED_CANCELLED = 2
        private const val NO_ENTITY = Int.MIN_VALUE

        private val MATERIAL_COUNT = Material.entries.size
        private val ENTITY_TYPE_COUNT = EntityType.entries.size
        private val NO_ENTITY_SUBS = arrayOf<EntitySub>()

        private inline fun <reified T> append(old: Array<T>?, item: T): Array<T> = if (old == null) arrayOf(item) else old + item

        /** [old] without [item] (one copy, no intermediate list), or null when nothing is left. */
        private inline fun <reified T> without(old: Array<T>, item: T): Array<T>? {
            val i = old.indexOfFirst { it === item }
            if (i < 0) return old
            if (old.size == 1) return null
            val out = arrayOfNulls<T>(old.size - 1)
            System.arraycopy(old, 0, out, 0, i)
            System.arraycopy(old, i + 1, out, i, old.size - i - 1)
            @Suppress("UNCHECKED_CAST")
            return out as Array<T>
        }
    }
}
