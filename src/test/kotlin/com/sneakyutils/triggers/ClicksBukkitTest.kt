package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock

class ClicksBukkitTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers
    private lateinit var player: PlayerMock
    private val log = ArrayList<String>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
        player = server.addPlayer("p")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun click(block: Block, action: Action = Action.RIGHT_CLICK_BLOCK, hand: EquipmentSlot? = EquipmentSlot.HAND): PlayerInteractEvent {
        val e = PlayerInteractEvent(player, action, null, block, BlockFace.UP, hand)
        server.pluginManager.callEvent(e)
        return e
    }

    private fun recorder(name: String, claim: Boolean = false) =
        BlockClickHandler { p, b, side, _ -> log += "$name ${p.name} ${b.x},${b.y},${b.z} $side"; claim }

    @Test
    fun positionClickDefaultsToRightMainHand() {
        val block = world.getBlockAt(10, 64, -3)
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("door"))
        click(block)
        click(block, hand = EquipmentSlot.OFF_HAND)
        click(block, Action.LEFT_CLICK_BLOCK)
        click(world.getBlockAt(10, 64, -4))
        assertEquals(listOf("door p 10,64,-3 RIGHT"), log)
    }

    @Test
    fun aRightClickWithAnEmptyMainHandAndAnOffHandItemIsDeliveredOnce() {
        // Paper fires PlayerInteractEvent per hand: the main hand first (here with no item), then the off hand,
        // because an empty main hand did nothing with the block. MagicSpells ran on both, so 65 passives carry a
        // short cooldown as a de-bounce. The default (main hand only) gives one call per physical click.
        val block = world.getBlockAt(10, 64, -3)
        val torch = org.bukkit.inventory.ItemStack(Material.TORCH)
        triggers.onBlockClick(owner, BlockKey.of(block), BlockClickHandler { _, _, _, e -> log += "block ${e.hand} ${e.item?.type}"; false })
        val stall = stand()
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), EntityClickHandler { p, _, _, e ->
            log += "entity ${(e as PlayerInteractEntityEvent).hand} ${p.inventory.itemInOffHand.type}"; false
        })
        player.inventory.setItemInMainHand(null)
        player.inventory.setItemInOffHand(torch)
        fun physicalClick() {
            server.pluginManager.callEvent(PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND))
            server.pluginManager.callEvent(PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, torch, block, BlockFace.UP, EquipmentSlot.OFF_HAND))
            server.pluginManager.callEvent(PlayerInteractEntityEvent(player, stall, EquipmentSlot.HAND))
            server.pluginManager.callEvent(PlayerInteractEntityEvent(player, stall, EquipmentSlot.OFF_HAND))
        }
        physicalClick()
        assertEquals(listOf("block HAND null", "entity HAND TORCH"), log,
            "one call each, from the main-hand event; the off-hand item is on the player, not on the event")

        // Both hands: two calls per physical click, so the handler de-bounces itself.
        log.clear()
        val both = ClickOptions.builder().hands(setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)).build()
        triggers.onBlockClick(owner, BlockKey.of(block), BlockClickHandler { _, _, _, e -> log += "both ${e.hand}"; false }, both)
        physicalClick()
        assertEquals(listOf("block HAND null", "both HAND", "both OFF_HAND", "entity HAND TORCH"), log)
    }

    @Test
    fun sidesHandsAndPhysical() {
        val block = world.getBlockAt(0, 64, 0)
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("both"),
            ClickOptions.builder().sides(ClickSide.BOTH).hands(setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)).build())
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("plate"), ClickOptions.builder().sides(ClickSide.PHYSICAL).build())
        click(block)
        click(block, Action.LEFT_CLICK_BLOCK, EquipmentSlot.OFF_HAND)
        click(block, Action.PHYSICAL, null)
        assertEquals(listOf("both p 0,64,0 RIGHT", "both p 0,64,0 LEFT", "plate p 0,64,0 PHYSICAL"), log)
    }

    @Test
    fun cancelEventOnlyWhenTheHandlerClaimsTheClick() {
        val a = world.getBlockAt(1, 64, 0)
        val b = world.getBlockAt(2, 64, 0)
        val cancel = ClickOptions.builder().cancelEvent(true).build()
        triggers.onBlockClick(owner, BlockKey.of(a), recorder("claims", claim = true), cancel)
        triggers.onBlockClick(owner, BlockKey.of(b), recorder("passes", claim = false), cancel)
        assertEquals(Event.Result.DENY, click(a).useInteractedBlock())
        assertTrue(click(b).useInteractedBlock() != Event.Result.DENY)
    }

    @Test
    fun aCancelledMainHandClickAlsoCancelsItsOffHandHalf() {
        // Found live (Paper 26.2): with an empty main hand the client then uses the off-hand item, so a cancelled
        // decorative "button" still got a block placed against it, and a cancelled cow click still milked the cow.
        val block = world.getBlockAt(5, 64, 0)
        val other = world.getBlockAt(6, 64, 0)
        val cancel = ClickOptions.builder().cancelEvent(true).build()
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("button", claim = true), cancel)
        triggers.onBlockClick(owner, BlockKey.of(other), recorder("plain"))
        val cobble = org.bukkit.inventory.ItemStack(Material.COBBLESTONE)
        fun half(b: Block, hand: EquipmentSlot) = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, if (hand == EquipmentSlot.OFF_HAND) cobble else null,
            b, BlockFace.UP, hand).also { server.pluginManager.callEvent(it) }
        assertEquals(Event.Result.DENY, half(block, EquipmentSlot.HAND).useInteractedBlock())
        val off = half(block, EquipmentSlot.OFF_HAND)
        assertEquals(Event.Result.DENY, off.useItemInHand(), "the off-hand half of the cancelled click is cancelled too")
        assertEquals(Event.Result.DENY, off.useInteractedBlock())
        assertTrue(half(other, EquipmentSlot.OFF_HAND).useItemInHand() != Event.Result.DENY, "another block: untouched")
        server.scheduler.performOneTick()
        assertTrue(half(block, EquipmentSlot.OFF_HAND).useItemInHand() != Event.Result.DENY, "a later tick: a click of its own")
        assertEquals(listOf("button p 5,64,0 RIGHT"), log, "still one handler call")

        // Entities: the same for the off-hand "interact at" event and its plain event.
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND),
            EntityClickHandler { _, _, _, _ -> log += "stand"; true }, ClickOptions.builder().cancelEvent(true).build())
        val s = stand()
        val main = PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.HAND).also { server.pluginManager.callEvent(it) }
        val offAt = PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.OFF_HAND).also { server.pluginManager.callEvent(it) }
        val offPlain = PlayerInteractEntityEvent(player, s, EquipmentSlot.OFF_HAND).also { server.pluginManager.callEvent(it) }
        assertTrue(main.isCancelled)
        assertTrue(offAt.isCancelled, "off-hand half of the cancelled entity click")
        assertTrue(offPlain.isCancelled)
        assertEquals(listOf("button p 5,64,0 RIGHT", "stand"), log)
        player.disconnect()
        assertEquals(0, (triggers as com.sneakyutils.triggers.service.TriggerService).clicks.pairingRecords, "quit forgets it")
    }

    @Test
    fun theOffHandHalfIsCancelledEvenWhenTheHandlerChangedTheBlockOrClosedItsSubscription() {
        // Regression (Codex review 3 #1): the replay ran after the "nothing subscribed here" returns, so a handler
        // that turned the block into another type, or closed its own (only) subscription, left the off-hand half live.
        val cancel = ClickOptions.builder().cancelEvent(true).build()
        val cobble = org.bukkit.inventory.ItemStack(Material.COBBLESTONE)
        fun half(b: Block, hand: EquipmentSlot) = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
            if (hand == EquipmentSlot.OFF_HAND) cobble else null, b, BlockFace.UP, hand).also { server.pluginManager.callEvent(it) }

        val ore = world.getBlockAt(5, 64, 0)
        ore.type = Material.STONE
        val type = triggers.onBlockType(owner, Material.STONE, BlockClickHandler { _, b, _, _ -> b.type = Material.DIRT; true }, cancel)
        assertEquals(Event.Result.DENY, half(ore, EquipmentSlot.HAND).useInteractedBlock())
        assertEquals(Event.Result.DENY, half(ore, EquipmentSlot.OFF_HAND).useItemInHand(), "the block changed type in the handler")
        type.close()

        server.scheduler.performOneTick()
        val button = world.getBlockAt(7, 64, 0)
        lateinit var once: Subscription
        once = triggers.onBlockClick(owner, BlockKey.of(button), BlockClickHandler { _, _, _, _ -> once.close(); true }, cancel)
        assertEquals(Event.Result.DENY, half(button, EquipmentSlot.HAND).useInteractedBlock())
        assertEquals(0, triggers.stats().count("block-click"), "no click subscription is left at all")
        assertEquals(Event.Result.DENY, half(button, EquipmentSlot.OFF_HAND).useItemInHand(), "the handler closed its subscription")

        // Entities: the only entity subscription closes itself; the plain event and the off-hand half still follow.
        server.scheduler.performOneTick()
        val s = stand()
        lateinit var stall: Subscription
        stall = triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND),
            EntityClickHandler { _, _, _, _ -> stall.close(); log += "stall"; true }, cancel)
        val at = PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.HAND).also { server.pluginManager.callEvent(it) }
        val plain = PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND).also { server.pluginManager.callEvent(it) }
        val offAt = PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.OFF_HAND).also { server.pluginManager.callEvent(it) }
        val offPlain = PlayerInteractEntityEvent(player, s, EquipmentSlot.OFF_HAND).also { server.pluginManager.callEvent(it) }
        assertTrue(at.isCancelled)
        assertTrue(plain.isCancelled, "the paired plain event, with no entity subscription left")
        assertTrue(offAt.isCancelled && offPlain.isCancelled, "the off-hand half")
        assertEquals(listOf("stall"), log)
        assertEquals(0, triggers.stats().count("entity-click"))
    }

    @Test
    fun cancelledClicksAreSkippedUnlessAsked() {
        val block = world.getBlockAt(3, 64, 0)
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("default"))
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("sees-cancelled"), ClickOptions.builder().ignoreCancelled(false).build())
        val e = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND)
        e.setUseInteractedBlock(Event.Result.DENY) // a protection plugin said no
        server.pluginManager.callEvent(e)
        assertEquals(listOf("sees-cancelled p 3,64,0 RIGHT"), log)
    }

    @Test
    fun typeClicksMatchEveryBlockOfTheMaterial() {
        world.getBlockAt(5, 64, 5).type = Material.LEVER
        world.getBlockAt(6, 64, 5).type = Material.STONE
        triggers.onBlockType(owner, Material.LEVER, recorder("lever"))
        click(world.getBlockAt(5, 64, 5))
        click(world.getBlockAt(6, 64, 5))
        assertEquals(listOf("lever p 5,64,5 RIGHT"), log)
        assertThrows(IllegalArgumentException::class.java) { triggers.onBlockType(owner, Material.DIAMOND, recorder("x")) }
    }

    @Test
    fun breaksByPositionAndTypeStopAfterACancel() {
        val ore = world.getBlockAt(7, 64, 7)
        ore.type = Material.IRON_ORE
        triggers.onBlockBreak(owner, BlockKey.of(ore)) { p, b, e ->
            log += "pos ${p.name} ${b.x}"
            e.isCancelled = true
        }
        triggers.onBlockBreakType(owner, Material.IRON_ORE) { _, _, _ -> log += "type" }
        player.simulateBlockBreak(ore)
        assertEquals(listOf("pos p 7"), log, "the type handler doesn't see a break the position handler cancelled")
        log.clear()
        val other = world.getBlockAt(8, 64, 7)
        other.type = Material.IRON_ORE
        player.simulateBlockBreak(other)
        assertEquals(listOf("type"), log)
    }

    @Test
    fun breakOptionsCanAskForCancelledBreaks() {
        // The city flowers sit in protected land: the break is usually cancelled by the protection plugin, and the
        // handler must still run (MagicSpells' ignore-cancelled: false).
        val flower = world.getBlockAt(3, 64, 3)
        flower.type = Material.POPPY
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOW)
            fun protect(e: org.bukkit.event.block.BlockBreakEvent) {
                if (e.block.x == 3) e.isCancelled = true
            }
        }, owner)
        val all = BreakOptions.builder().ignoreCancelled(false).build()
        assertFalse(all.ignoreCancelled)
        assertTrue(BreakOptions.DEFAULT.ignoreCancelled)
        triggers.onBlockBreak(owner, BlockKey.of(flower), { _, _, e -> log += "flower cancelled=${e.isCancelled}" }, all)
        triggers.onBlockBreakType(owner, Material.POPPY, { _, _, e -> log += "poppy type cancelled=${e.isCancelled}" }, all)
        triggers.onBlockBreakType(owner, Material.POPPY) { _, _, _ -> log += "default" }
        player.simulateBlockBreak(flower)
        assertEquals(listOf("flower cancelled=true", "poppy type cancelled=true"), log, "the default skips it")
        log.clear()
        val free = world.getBlockAt(4, 64, 3)
        free.type = Material.POPPY
        player.simulateBlockBreak(free)
        assertEquals(listOf("poppy type cancelled=false", "default"), log)
    }

    @Test
    fun registrationChecks() {
        assertThrows(IllegalArgumentException::class.java) {
            triggers.onBlockClick(owner, BlockKey(world.uid, 0, 5000, 0), recorder("x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), { _, _, _, _ -> false },
                ClickOptions.builder().sides(ClickSide.PHYSICAL).build())
        }
    }

    @Test
    fun closeOwnerDisableAndWorldUnloadRemoveClicks() {
        val block = world.getBlockAt(0, 64, 0)
        val sub = triggers.onBlockClick(owner, BlockKey.of(block), recorder("a"))
        sub.close()
        click(block)
        assertEquals(emptyList<String>(), log)

        val instance = server.addSimpleWorld("instance")
        val there = triggers.onBlockClick(owner, BlockKey.of(instance.getBlockAt(0, 64, 0)), recorder("b"))
        val here = triggers.onBlockClick(owner, BlockKey.of(block), recorder("c"))
        server.unloadWorld(instance, false)
        assertFalse(there.active)
        assertTrue(here.active)
        server.pluginManager.disablePlugin(owner)
        assertFalse(here.active)
        click(block)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun aThrowingClickHandlerDoesNotStopOthers() {
        val block = world.getBlockAt(0, 64, 0)
        triggers.onBlockClick(owner, BlockKey.of(block)) { _, _, _, _ -> error("boom") }
        triggers.onBlockClick(owner, BlockKey.of(block), recorder("ok"))
        click(block)
        assertEquals(listOf("ok p 0,64,0 RIGHT"), log)
        assertEquals(1L, triggers.stats().counter("handler.errors"))
    }

    // ---------------------------------------------------------------- entities

    private fun stand(): ArmorStand = world.spawn(Location(world, 0.5, 64.0, 0.5), ArmorStand::class.java)

    private fun entityRecorder(name: String) = EntityClickHandler { _, e, side, _ -> log += "$name ${e.type} $side"; false }

    @Test
    fun entityMatchesByTypeTagPdcAndCitizens() {
        val key = NamespacedKey(owner, "kiosk")
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), entityRecorder("type"))
        triggers.onEntityClick(owner, EntityMatch.scoreboardTag("vendor"), entityRecorder("tag"))
        triggers.onEntityClick(owner, EntityMatch.pdcKey(key).withType(EntityType.ARMOR_STAND), entityRecorder("pdc"))
        triggers.onEntityClick(owner, EntityMatch.citizensNpc(7), entityRecorder("npc7"))
        triggers.onEntityClick(owner, EntityMatch.citizensNpc(null), entityRecorder("anyNpc"))

        val plain = stand()
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, plain, EquipmentSlot.HAND))
        assertEquals(listOf("type ARMOR_STAND RIGHT"), log)
        log.clear()

        val tagged = stand()
        tagged.addScoreboardTag("vendor")
        tagged.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
        tagged.setMetadata("NPC", FixedMetadataValue(owner, true))
        tagged.setMetadata("NPC-ID", FixedMetadataValue(owner, 7))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, tagged, EquipmentSlot.HAND))
        assertEquals(setOf("type ARMOR_STAND RIGHT", "tag ARMOR_STAND RIGHT", "pdc ARMOR_STAND RIGHT",
            "npc7 ARMOR_STAND RIGHT", "anyNpc ARMOR_STAND RIGHT"), log.toSet())
    }

    @Test
    fun notCitizensSkipsNpcsOfTheType() {
        // MagicSpells' rightclickentity excluded Citizens NPCs unless asked for them; 51 player trades rely on it.
        val trades = EntityMatch.type(EntityType.PLAYER).notCitizens()
        assertTrue(trades.excludesCitizens)
        assertEquals(EntityType.PLAYER, trades.type)
        triggers.onEntityClick(owner, trades, EntityClickHandler { _, e, _, _ -> log += "trade ${e.name}"; false })
        triggers.onEntityClick(owner, EntityMatch.scoreboardTag("vendor").notCitizens().withType(EntityType.ARMOR_STAND), entityRecorder("vendor"))
        val customer = server.addPlayer("customer")
        val npc = server.addPlayer("npc")
        npc.setMetadata("NPC", FixedMetadataValue(owner, true))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, customer, EquipmentSlot.HAND))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, npc, EquipmentSlot.HAND))
        val stall = stand().also { it.addScoreboardTag("vendor") }
        val npcStall = stand().also { it.addScoreboardTag("vendor"); it.setMetadata("NPC", FixedMetadataValue(owner, true)) }
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, stall, EquipmentSlot.HAND))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, npcStall, EquipmentSlot.HAND))
        assertEquals(listOf("trade customer", "vendor ARMOR_STAND RIGHT"), log)
        assertThrows(IllegalArgumentException::class.java) { EntityMatch.citizensNpc(null).notCitizens() }
        assertTrue(EntityMatch.type(EntityType.PLAYER).notCitizens().toString().contains("notCitizens"))
    }

    @Test
    fun interactAtAndPlainEventsDispatchOnce() {
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), entityRecorder("s"))
        val s = stand()
        server.pluginManager.callEvent(PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.HAND))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND))
        assertEquals(1, log.size, "the plain event after the 'at' event is the same click")
        server.scheduler.performOneTick()
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND))
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, s, EquipmentSlot.OFF_HAND))
        assertEquals(2, log.size, "a plain click alone counts; the off hand is filtered by default")
    }

    @Test
    fun aClaimedClickCancelsBothHalvesOfThePairedEntityEvents() {
        // Regression (Codex M4 #3): the plain event after the "at" event returned early, uncancelled.
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND),
            EntityClickHandler { _, _, _, _ -> log += "claimed"; true }, ClickOptions.builder().cancelEvent(true).build())
        val s = stand()
        val at = PlayerInteractAtEntityEvent(player, s, Vector(), EquipmentSlot.HAND)
        val plain = PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND)
        server.pluginManager.callEvent(at)
        server.pluginManager.callEvent(plain)
        assertTrue(at.isCancelled)
        assertTrue(plain.isCancelled, "the paired plain event carries the same decision")
        assertEquals(listOf("claimed"), log)
        // The pairing is used up: another plain click in the same tick is its own click.
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND))
        assertEquals(listOf("claimed", "claimed"), log)
    }

    @Test
    fun interleavedPairsFromTwoPlayersAndTwoHandsDispatchOnceEach() {
        // Regression (Codex full review #2): one server-wide pairing record; B's "at" event overwrote A's, so A's
        // plain event dispatched again.
        val q = server.addPlayer("q")
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND),
            EntityClickHandler { p, _, _, e -> log += "${p.name} ${(e as PlayerInteractEntityEvent).hand}"; p.name == "p" },
            ClickOptions.builder().cancelEvent(true).hands(setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)).build())
        val s = stand()
        fun at(who: PlayerMock, hand: EquipmentSlot) =
            PlayerInteractAtEntityEvent(who, s, Vector(), hand).also { server.pluginManager.callEvent(it) }
        fun plain(who: PlayerMock, hand: EquipmentSlot) =
            PlayerInteractEntityEvent(who, s, hand).also { server.pluginManager.callEvent(it) }
        at(player, EquipmentSlot.HAND)
        at(q, EquipmentSlot.HAND)
        at(player, EquipmentSlot.OFF_HAND)
        val pMain = plain(player, EquipmentSlot.HAND)
        val qMain = plain(q, EquipmentSlot.HAND)
        val pOff = plain(player, EquipmentSlot.OFF_HAND)
        assertEquals(listOf("p HAND", "q HAND", "p OFF_HAND"), log)
        assertTrue(pMain.isCancelled, "p's claim carries over to p's plain event")
        assertFalse(qMain.isCancelled, "q's handler didn't claim")
        assertTrue(pOff.isCancelled)
        // Records are used up, and expire with the tick.
        at(q, EquipmentSlot.HAND)
        server.scheduler.performOneTick()
        plain(q, EquipmentSlot.HAND)
        assertEquals(listOf("p HAND", "q HAND", "p OFF_HAND", "q HAND", "q HAND"), log)
        q.disconnect()
        player.disconnect()
        assertEquals(0, (triggers as com.sneakyutils.triggers.service.TriggerService).clicks.pairingRecords,
            "quit forgets the pairing slots")
    }

    @Test
    fun leftClicksComeFromTheAttackEventAndCanBeCancelled() {
        triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND),
            EntityClickHandler { _, e, side, _ -> log += "${e.type} $side"; true },
            ClickOptions.builder().sides(ClickSide.LEFT).cancelEvent(true).build())
        val s = stand()
        val attack = PrePlayerAttackEntityEvent(player, s, true)
        server.pluginManager.callEvent(attack)
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, s, EquipmentSlot.HAND))
        assertEquals(listOf("ARMOR_STAND LEFT"), log)
        assertTrue(attack.isCancelled)
    }
}
