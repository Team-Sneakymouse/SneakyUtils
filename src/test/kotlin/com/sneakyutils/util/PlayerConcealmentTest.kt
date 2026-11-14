package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.plugin.PluginMock
import org.mockbukkit.mockbukkit.world.WorldMock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class PlayerConcealmentTest {

    private lateinit var server: ServerMock
    private lateinit var world: WorldMock
    private lateinit var poses: PluginMock
    private lateinit var cctv: PluginMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        MockBukkit.load(SneakyUtils::class.java)
        world = WorldMock(Material.STONE, 64)
        server.addWorld(world)
        poses = MockBukkit.createMockPlugin("SneakyPoses")
        cctv = MockBukkit.createMockPlugin("SneakyCCTV")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun player(name: String): PlayerMock = server.addPlayer(name).also { it.teleport(world.spawnLocation) }

    @Test
    fun concealMakesInvisibleAndRevealRestores() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        assertTrue(p.isInvisible)
        assertTrue(PlayerUtil.isConcealed(p))
        PlayerUtil.reveal(poses, p)
        assertFalse(p.isInvisible)
        assertFalse(PlayerUtil.isConcealed(p))
    }

    @Test
    fun revealKeepsInvisibilityTheyAlreadyHad() {
        val p = player("a")
        p.isInvisible = true
        PlayerUtil.conceal(poses, p)
        PlayerUtil.reveal(poses, p)
        assertTrue(p.isInvisible)
    }

    @Test
    fun oneOwnerCannotRevealAnothersConcealment() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        PlayerUtil.conceal(cctv, p)
        PlayerUtil.reveal(poses, p)
        assertTrue(PlayerUtil.isConcealed(p))
        assertTrue(p.isInvisible)
        PlayerUtil.reveal(poses, p)                                   // again: still counted once
        assertTrue(PlayerUtil.isConcealed(p))
        PlayerUtil.reveal(cctv, p)
        assertFalse(PlayerUtil.isConcealed(p))
        assertFalse(p.isInvisible)
    }

    @Test
    fun revealByAnOwnerThatNeverConcealedDoesNothing() {
        val p = player("a")
        PlayerUtil.reveal(poses, p)
        p.isInvisible = true
        PlayerUtil.reveal(poses, p)
        assertTrue(p.isInvisible)
    }

    @Test
    fun invisibilityIsKeptWhileConcealed() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        p.isInvisible = false
        server.scheduler.performOneTick()
        assertTrue(p.isInvisible)
    }

    @Test
    fun ridersAreHiddenFromEveryoneUntilRevealed() {
        val p = player("a")
        val other = player("b")
        val cosmetic = world.spawn(p.location, ItemDisplay::class.java)
        p.addPassenger(cosmetic)
        PlayerUtil.conceal(poses, p)
        assertFalse(other.canSee(cosmetic))
        assertFalse(p.canSee(cosmetic))
        PlayerUtil.reveal(poses, p)
        assertTrue(other.canSee(cosmetic))
        assertTrue(p.canSee(cosmetic))
    }

    @Test
    fun laterRidersAreHiddenAndShownWhenTheyGetOff() {
        val p = player("a")
        val other = player("b")
        PlayerUtil.conceal(poses, p)
        val tag = world.spawn(p.location, ItemDisplay::class.java)
        p.addPassenger(tag)
        server.scheduler.performOneTick()
        assertFalse(other.canSee(tag))
        p.removePassenger(tag)
        server.scheduler.performOneTick()
        assertTrue(other.canSee(tag))
    }

    @Test
    fun ridingPlayersStayVisible() {
        val p = player("a")
        val rider = player("b")
        val other = player("c")
        p.addPassenger(rider)
        PlayerUtil.conceal(poses, p)
        assertTrue(other.canSee(rider))
    }

    @Test
    fun viewersJoiningLaterDontSeeRiders() {
        val p = player("a")
        val cosmetic = world.spawn(p.location, ItemDisplay::class.java)
        p.addPassenger(cosmetic)
        PlayerUtil.conceal(poses, p)
        val late = player("late")
        server.scheduler.performOneTick()
        assertFalse(late.canSee(cosmetic))
    }

    @Test
    fun quittingRevealsAndForgets() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        p.disconnect()
        assertFalse(p.isInvisible)
        assertFalse(PlayerUtil.isConcealed(p))
    }

    @Test
    fun disableRevealsEveryone() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        server.pluginManager.disablePlugin(server.pluginManager.getPlugin("SneakyUtils")!!)
        assertFalse(p.isInvisible)
        assertFalse(PlayerUtil.isConcealed(p))
    }

    @Test
    fun creativeWritesToMaskedSlotsAreCancelled() {
        val p = player("a")
        PlayerUtil.conceal(poses, p)
        val concealment = SneakyUtils.getPlayerConcealment()!!
        val hotbar = creative(p, 36)
        val main = creative(p, 9)
        concealment.onCreative(hotbar)
        concealment.onCreative(main)
        verify(hotbar).isCancelled = true
        verify(main, never()).isCancelled = true
    }

    private fun creative(p: Player, raw: Int): InventoryCreativeEvent {
        val e = mock(InventoryCreativeEvent::class.java)
        `when`(e.whoClicked).thenReturn(p)
        `when`(e.rawSlot).thenReturn(raw)
        return e
    }

    @Test
    fun maskedSlotsAreArmourHotbarAndOffhand() {
        val menu = (0..45).filter { ConcealPackets.menuSlotMasked(it) }
        assertEquals((5..8).toList() + (36..45).toList(), menu)
        val inventory = (0..40).filter { ConcealPackets.inventorySlotMasked(it) }
        assertEquals((0..8).toList() + (36..40).toList(), inventory)
    }
}
