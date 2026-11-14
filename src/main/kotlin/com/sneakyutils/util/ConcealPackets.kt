package com.sneakyutils.util

import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The packet half of [PlayerConcealment]: a handler in every player's connection pipeline, before `packet_handler`,
 * that rewrites what the server sends.
 *
 * - **To other players:** equipment packets about a concealed player go out with every slot empty. This includes the
 *   ones bundled into the spawn when a player comes into range.
 * - **To the concealed player:** their own armour, hotbar and offhand slots go out empty, so they don't see the items
 *   on themselves in third person or in their hand in first person.
 *
 * The server's real items are never touched. There's no API for this, so it uses the server's own classes by
 * reflection (Paper runs Mojang's names). If that fails, the console says so once and the gear shows again. The
 * invisibility and rider hiding in [PlayerConcealment] keep working either way.
 */
internal class ConcealPackets(private val plugin: Plugin) {

    /** Entity id → UUID of each concealed player (read on Netty threads). */
    private val ids = ConcurrentHashMap<Int, UUID>()
    private val idOf = ConcurrentHashMap<UUID, Int>()

    /** Concealed players whose own gear slots are masked on their own screen. */
    private val selves: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var broken = false

    @Volatile
    private var loaded = false

    private lateinit var empty: Any
    private lateinit var equipment: Class<*>
    private lateinit var equipmentEntity: MethodHandle
    private lateinit var equipmentSlots: MethodHandle
    private lateinit var newEquipment: MethodHandle
    private lateinit var pairOf: MethodHandle
    private lateinit var pairFirst: MethodHandle
    private lateinit var bundle: Class<*>
    private lateinit var bundleParts: MethodHandle
    private lateinit var newBundle: MethodHandle
    private lateinit var setContent: Class<*>
    private lateinit var contentId: MethodHandle
    private lateinit var contentState: MethodHandle
    private lateinit var contentItems: MethodHandle
    private lateinit var contentCarried: MethodHandle
    private lateinit var newContent: MethodHandle
    private lateinit var setSlot: Class<*>
    private lateinit var slotId: MethodHandle
    private lateinit var slotState: MethodHandle
    private lateinit var slotSlot: MethodHandle
    private lateinit var newSlot: MethodHandle
    private lateinit var setInventory: Class<*>
    private lateinit var inventorySlot: MethodHandle
    private lateinit var newInventory: MethodHandle

    @Synchronized
    private fun load() {
        if (loaded) return
        val l = MethodHandles.publicLookup()
        val int = Int::class.javaPrimitiveType!!
        val stack = Class.forName("net.minecraft.world.item.ItemStack")
        val game = "net.minecraft.network.protocol.game."

        equipment = Class.forName(game + "ClientboundSetEquipmentPacket")
        equipmentEntity = l.findVirtual(equipment, "getEntity", MethodType.methodType(int))
        equipmentSlots = l.findVirtual(equipment, "getSlots", MethodType.methodType(List::class.java))
        newEquipment = l.findConstructor(equipment, MethodType.methodType(Void.TYPE, int, List::class.java))
        val pair = Class.forName("com.mojang.datafixers.util.Pair")
        pairOf = l.findStatic(pair, "of", MethodType.methodType(pair, Any::class.java, Any::class.java))
        pairFirst = l.findVirtual(pair, "getFirst", MethodType.methodType(Any::class.java))

        bundle = Class.forName(game + "ClientboundBundlePacket")
        bundleParts = l.findVirtual(bundle, "subPackets", MethodType.methodType(Iterable::class.java))
        newBundle = l.findConstructor(bundle, MethodType.methodType(Void.TYPE, Iterable::class.java))

        setContent = Class.forName(game + "ClientboundContainerSetContentPacket")
        contentId = l.findVirtual(setContent, "containerId", MethodType.methodType(int))
        contentState = l.findVirtual(setContent, "stateId", MethodType.methodType(int))
        contentItems = l.findVirtual(setContent, "items", MethodType.methodType(List::class.java))
        contentCarried = l.findVirtual(setContent, "carriedItem", MethodType.methodType(stack))
        newContent = l.findConstructor(setContent, MethodType.methodType(Void.TYPE, int, int, List::class.java, stack))

        setSlot = Class.forName(game + "ClientboundContainerSetSlotPacket")
        slotId = l.findVirtual(setSlot, "getContainerId", MethodType.methodType(int))
        slotState = l.findVirtual(setSlot, "getStateId", MethodType.methodType(int))
        slotSlot = l.findVirtual(setSlot, "getSlot", MethodType.methodType(int))
        newSlot = l.findConstructor(setSlot, MethodType.methodType(Void.TYPE, int, int, int, stack))

        setInventory = Class.forName(game + "ClientboundSetPlayerInventoryPacket")
        inventorySlot = l.findVirtual(setInventory, "slot", MethodType.methodType(int))
        newInventory = l.findConstructor(setInventory, MethodType.methodType(Void.TYPE, int, stack))

        empty = stack.getField("EMPTY").get(null)
        loaded = true
    }

    // ------------------------------------------------------------------ handlers

    /** Adds the handler to p's connection (each player, since anyone can see a concealed player). */
    fun inject(p: Player) {
        if (broken) return
        try {
            load()
            val ch = channel(p)
            ch.eventLoop().execute {
                if (ch.isOpen && ch.pipeline().get(HANDLER) == null && ch.pipeline().get("packet_handler") != null)
                    ch.pipeline().addBefore("packet_handler", HANDLER, Handler(p.uniqueId))
            }
        } catch (ex: Throwable) {
            fail(ex)
        }
    }

    /** Takes the handler off p's connection again (on disable, so nothing of this plugin is left in it). */
    fun eject(p: Player) {
        if (!loaded) return
        try {
            val ch = channel(p)
            ch.eventLoop().execute {
                if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER)
            }
        } catch (_: Throwable) {
            // their connection's already gone
        }
    }

    // ------------------------------------------------------------------ state (main thread writes)

    /** Starts masking p, then sends everyone (p included) what they should see now. */
    fun conceal(p: Player) {
        track(p)
        selves.add(p.uniqueId)
        val none = HashMap<EquipmentSlot, ItemStack?>()
        for (slot in WORN) none[slot] = ItemStack.empty()
        for (v in p.world.players) if (v != p) v.sendEquipmentChange(p, none)
        p.updateInventory()
    }

    /** Stops masking p, then sends everyone (p included) the real items again. */
    fun reveal(p: Player) {
        forget(p.uniqueId)
        if (!p.isOnline) return
        val worn = HashMap<EquipmentSlot, ItemStack?>()
        for (slot in WORN) worn[slot] = p.inventory.getItem(slot)
        for (v in p.world.players) if (v != p) v.sendEquipmentChange(p, worn)
        p.updateInventory()
    }

    /** Keeps p's entity id current (the one other players' packets about them carry). */
    fun track(p: Player) {
        val id = p.entityId
        val old = idOf.put(p.uniqueId, id)
        if (old != null && old != id) ids.remove(old)
        ids[id] = p.uniqueId
    }

    fun forget(player: UUID) {
        idOf.remove(player)?.let { ids.remove(it) }
        selves.remove(player)
    }

    fun masksSelf(player: UUID) = selves.contains(player)

    // ------------------------------------------------------------------ rewriting (Netty threads)

    private fun rewrite(viewer: UUID, msg: Any): Any {
        val c = msg.javaClass
        if (c == equipment) {
            val owner = ids[equipmentEntity.invoke(msg) as Int] ?: return msg
            if (owner == viewer) return msg                          // a plugin's own fake for them (CCTV's static)
            @Suppress("UNCHECKED_CAST")
            val slots = equipmentSlots.invoke(msg) as List<Any>
            val none = slots.map { pairOf.invoke(pairFirst.invoke(it), empty) }
            return newEquipment.invoke(equipmentEntity.invoke(msg) as Int, none)
        }
        if (c == bundle) {
            @Suppress("UNCHECKED_CAST")
            val parts = bundleParts.invoke(msg) as Iterable<Any>
            var changed = false
            val out = ArrayList<Any>()
            for (part in parts) {
                val r = rewrite(viewer, part)
                if (r !== part) changed = true
                out.add(r)
            }
            return if (changed) newBundle.invoke(out) else msg
        }
        if (!selves.contains(viewer)) return msg
        if (c == setContent) {
            val id = contentId.invoke(msg) as Int
            if (id != INVENTORY_MENU) return msg
            @Suppress("UNCHECKED_CAST")
            val items = ArrayList(contentItems.invoke(msg) as List<Any?>)
            for (i in items.indices) if (menuSlotMasked(i)) items[i] = empty
            return newContent.invoke(id, contentState.invoke(msg) as Int, items, contentCarried.invoke(msg))
        }
        if (c == setSlot) {
            val id = slotId.invoke(msg) as Int
            val slot = slotSlot.invoke(msg) as Int
            if (id != INVENTORY_MENU || !menuSlotMasked(slot)) return msg
            return newSlot.invoke(id, slotState.invoke(msg) as Int, slot, empty)
        }
        if (c == setInventory) {
            val slot = inventorySlot.invoke(msg) as Int
            if (!inventorySlotMasked(slot)) return msg
            return newInventory.invoke(slot, empty)
        }
        return msg
    }

    private inner class Handler(private val viewer: UUID) : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            var out = msg
            if (!broken && (ids.isNotEmpty() || selves.contains(viewer))) {
                try {
                    out = rewrite(viewer, msg)
                } catch (ex: Throwable) {
                    fail(ex)
                }
            }
            super.write(ctx, out, promise)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun channel(p: Player): Channel {
        val sp = p.javaClass.getMethod("getHandle").invoke(p)
        val listener = sp.javaClass.getField("connection").get(sp)
        val connection = findField(listener.javaClass, "connection").get(listener)
        return connection.javaClass.getField("channel").get(connection) as Channel
    }

    private fun findField(c: Class<*>, name: String): Field {
        var k: Class<*>? = c
        while (k != null) {
            try {
                return k.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                k = k.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun fail(ex: Throwable) {
        if (broken) return
        broken = true
        val log = { plugin.logger.warning("Couldn't mask concealed players' gear ($ex); their held items and armour will show.") }
        if (Bukkit.isPrimaryThread()) log() else Bukkit.getScheduler().runTask(plugin, Runnable { log() })
    }

    companion object {
        private const val HANDLER = "sneakyutils_conceal"
        private const val INVENTORY_MENU = 0

        val WORN = arrayOf(
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        )

        /** Slots of the player's own inventory menu (container 0) masked on their screen: armour, hotbar, offhand. */
        @JvmStatic
        fun menuSlotMasked(slot: Int) = slot in 5..8 || slot in 36..45

        /** The same slots as indexed in the player's Inventory (set-player-inventory packets). */
        @JvmStatic
        fun inventorySlotMasked(slot: Int) = slot in 0..8 || slot in 36..40
    }
}
