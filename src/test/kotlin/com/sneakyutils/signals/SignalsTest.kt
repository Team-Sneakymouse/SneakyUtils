package com.sneakyutils.signals

import com.sneakyutils.SneakyUtils
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

class SignalsTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        player = server.addPlayer("p")
    }

    @AfterEach
    fun tearDown() {
        HandlerList.unregisterAll(owner)
        MockBukkit.unmock()
    }

    @Test
    fun withoutListenersTheHelpersReturnTheDefaults() {
        val loc = Location(server.getWorld("world"), 1.0, 2.0, 3.0)
        assertTrue(ArrestEvent.getHandlerList().registeredListeners.isEmpty())
        ArrestEvent.fire(null, player, "theft")
        ChainedEvent.fire(player, null)
        DutyChangedEvent.fire(player, true, "paladin")
        ActivityEvent.fire(player, "catch", 1)
    }

    @Test
    fun listenersHearEverySignal() {
        val heard = ArrayList<String>()
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun arrest(e: ArrestEvent) {
                heard += "arrest ${e.paladin?.name} ${e.suspect.name} ${e.reason}"
                assertNull(e.paladin)
            }

            @EventHandler
            fun chained(e: ChainedEvent) {
                heard += "chained ${e.target.name} by ${e.by?.name}"
            }

            @EventHandler
            fun duty(e: DutyChangedEvent) {
                heard += "duty ${e.player.name} ${e.onDuty} ${e.role}"
            }

            @EventHandler
            fun activity(e: ActivityEvent) {
                assertThrows(UnsupportedOperationException::class.java) { (e.context as MutableMap)["x"] = "y" }
                heard += "activity ${e.activity} ${e.amount} ${e.context} at ${e.location?.blockX} on ${e.target?.type}"
            }
        }, owner)
        val loc = Location(server.getWorld("world"), 1.0, 2.0, 3.0)
        ArrestEvent.fire(null, player, "theft")
        ChainedEvent.fire(player, player)
        DutyChangedEvent.fire(player, false, "paladin")
        val context = HashMap(mapOf("block" to "iron_ore"))
        ActivityEvent.fire(player, "mine", 3, context)
        context["block"] = "changed after firing"
        val cow = server.getWorld("world")!!.spawnEntity(loc, org.bukkit.entity.EntityType.COW)
        val where = loc.clone()
        ActivityEvent.fire(player, "harvest", 1, mapOf("mob" to "cow"), where, cow)
        where.x = 99.0 // the event kept its own copy
        assertEquals(
            listOf(
                "arrest null p theft", "chained p by p", "duty p false paladin",
                "activity mine 3 {block=iron_ore} at null on null", "activity harvest 1 {mob=cow} at 1 on COW",
            ),
            heard,
        )
    }
}
