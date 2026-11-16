package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import org.bukkit.Location
import org.bukkit.Material
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
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.SplittableRandom

/**
 * Randomized clicks against the pairing rules: a click's halves (main hand, then off hand; "interact at", then plain)
 * must follow the first half's decision in the same tick, whatever the handlers did in between: claim, close their
 * own or another subscription, change the block, subscribe anew. Other players' clicks interleave; ticks pass.
 *
 * Invariants checked after every click: the off-hand half is cancelled exactly when the main-hand half was (same
 * tick; never across ticks), a plain entity event carries its "at" event's decision, and each subscription is called
 * at most once per hand per click. `-Dtriggers.soak=N` multiplies the rounds.
 */
class ClickPairingFuzzTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers
    private lateinit var players: List<PlayerMock>

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
        players = listOf(server.addPlayer("p"), server.addPlayer("q"))
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private val rounds: Int get() = System.getProperty("triggers.soak")?.toLongOrNull()?.coerceIn(1, 1000)?.toInt() ?: 1

    @Test
    fun clickHalvesFollowTheFirstHalfWhateverHandlersDo() {
        val cancel = ClickOptions.builder().cancelEvent(true).build()
        val both = ClickOptions.builder().hands(setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)).build()
        val blocks = listOf(world.getBlockAt(1, 64, 0), world.getBlockAt(2, 64, 0))
        val stands = listOf(0, 1).map { world.spawn(Location(world, it + 0.5, 64.0, 3.5), ArmorStand::class.java) }
        val item = ItemStack(Material.COBBLESTONE)
        var clicks = 0
        var replays = 0
        repeat(400 * rounds) { seq ->
            val r = SplittableRandom(seq * 7_919L + 13)
            for (b in blocks) b.type = if (r.nextBoolean()) Material.STONE else Material.OAK_PLANKS
            val subs = ArrayList<Subscription>()
            val calls = HashMap<Pair<Subscription, String>, Int>()
            lateinit var behave: (Subscription, org.bukkit.entity.Player, Block?) -> Boolean

            fun subscribeOne() {
                lateinit var self: Subscription
                val claimOptions = if (r.nextBoolean()) cancel else ClickOptions.DEFAULT
                self = when (r.nextInt(5)) {
                    0 -> triggers.onBlockClick(owner, BlockKey.of(blocks[r.nextInt(2)]), BlockClickHandler { who, b, _, _ -> behave(self, who, b) }, claimOptions)
                    1 -> triggers.onBlockType(owner, if (r.nextBoolean()) Material.STONE else Material.OAK_PLANKS,
                        BlockClickHandler { who, b, _, _ -> behave(self, who, b) }, claimOptions)
                    2 -> triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), EntityClickHandler { who, _, _, _ -> behave(self, who, null) }, claimOptions)
                    // Both hands, never claiming: they see both halves as they come, and must not change the outcome.
                    3 -> triggers.onBlockClick(owner, BlockKey.of(blocks[r.nextInt(2)]), BlockClickHandler { who, _, _, _ -> calls.merge(self to who.name, 1, Int::plus); false }, both)
                    else -> triggers.onEntityClick(owner, EntityMatch.type(EntityType.ARMOR_STAND), EntityClickHandler { who, _, _, _ -> calls.merge(self to who.name, 1, Int::plus); false }, both)
                }
                subs += self
            }
            behave = { self, who, block ->
                calls.merge(self to who.name, 1, Int::plus)
                when (r.nextInt(8)) {
                    0 -> self.close()
                    1 -> subs.randomOrNull(r)?.close()
                    2 -> block?.type = if (block?.type == Material.STONE) Material.DIRT else Material.STONE
                    3 -> subscribeOne()
                }
                r.nextInt(3) != 0
            }
            repeat(1 + r.nextInt(4)) { subscribeOne() }

            repeat(4 + r.nextInt(8)) {
                clicks++
                calls.clear()
                val p = players[r.nextInt(2)]
                val other = players[1 - players.indexOf(p)]
                val tickBetween = r.nextInt(6) == 0
                val interleave = r.nextInt(4) == 0
                if (r.nextBoolean()) {
                    val b = blocks[r.nextInt(2)]
                    fun half(who: PlayerMock, hand: EquipmentSlot) = PlayerInteractEvent(who, Action.RIGHT_CLICK_BLOCK,
                        if (hand == EquipmentSlot.OFF_HAND) item else null, b, BlockFace.UP, hand).also { server.pluginManager.callEvent(it) }
                    val main = half(p, EquipmentSlot.HAND)
                    val mainDenied = main.useInteractedBlock() == Event.Result.DENY
                    if (interleave) half(other, EquipmentSlot.HAND)
                    if (tickBetween) server.scheduler.performOneTick()
                    if (r.nextInt(4) != 0) {
                        val off = half(p, EquipmentSlot.OFF_HAND)
                        val offDenied = off.useItemInHand() == Event.Result.DENY
                        val expected = mainDenied && !tickBetween
                        assertEquals(expected, offDenied, "sequence $seq: block off-hand half (main denied $mainDenied, tick between $tickBetween)")
                        if (expected) replays++
                    }
                } else {
                    val s = stands[r.nextInt(2)]
                    fun at(who: PlayerMock, hand: EquipmentSlot) =
                        PlayerInteractAtEntityEvent(who, s, Vector(), hand).also { server.pluginManager.callEvent(it) }
                    fun plain(who: PlayerMock, hand: EquipmentSlot) =
                        PlayerInteractEntityEvent(who, s, hand).also { server.pluginManager.callEvent(it) }
                    val atMain = at(p, EquipmentSlot.HAND)
                    if (interleave) at(other, EquipmentSlot.HAND)
                    if (r.nextInt(5) != 0) assertEquals(atMain.isCancelled, plain(p, EquipmentSlot.HAND).isCancelled, "sequence $seq: plain main half")
                    if (tickBetween) server.scheduler.performOneTick()
                    if (r.nextInt(3) != 0) {
                        val atOff = at(p, EquipmentSlot.OFF_HAND)
                        val expected = atMain.isCancelled && !tickBetween
                        assertEquals(expected, atOff.isCancelled, "sequence $seq: entity off-hand half")
                        if (expected) replays++
                        if (r.nextBoolean()) assertEquals(atOff.isCancelled, plain(p, EquipmentSlot.OFF_HAND).isCancelled, "sequence $seq: plain off half")
                    }
                }
                for ((key, n) in calls) assertTrue(n <= 2, "sequence $seq: ${key.first} called $n times for ${key.second}'s click")
                if (r.nextInt(3) == 0) server.scheduler.performOneTick()
            }
            for (s in subs) s.close()
            server.scheduler.performOneTick()
        }
        println("ClickPairingFuzzTest: $clicks clicks, $replays off-hand halves cancelled by replay")
        assertTrue(replays > clicks / 20, "the replay path was exercised: $replays of $clicks")
    }

    private fun <T> List<T>.randomOrNull(r: SplittableRandom): T? = if (isEmpty()) null else this[r.nextInt(size)]
}
