package com.sneakyutils.signals

import com.sneakyutils.SneakyUtils
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ChronicleEventTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var player: PlayerMock
    private val heard = CopyOnWriteArrayList<ChronicleEvent>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Recorder")
        player = server.addPlayer("p")
    }

    @AfterEach
    fun tearDown() {
        HandlerList.unregisterAll(owner)
        MockBukkit.unmock()
    }

    private fun listen() {
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun on(e: ChronicleEvent) {
                heard += e
            }
        }, owner)
    }

    @Test
    fun withoutAListenerAReportDoesNothing() {
        assertFalse(ChronicleEvent.hasListeners())
        val builder = ChronicleEvent.of("sneakylegendarys.orb_placed")
        assertSame(builder, ChronicleEvent.of("chat.message"), "one shared builder that ignores everything")
        builder.actor(player).target(player).thing("orb", 3).detail("where", "shrine").text("hello")
            .heardBy(listOf(player.uniqueId)).at(player.location).time(Instant.now()).until(Instant.now()).fire()
        // nothing it was given stayed in the shared builder
        listen()
        ChronicleEvent.of("chat.message").fire()
        assertNull(heard.single().actor)
        assertTrue(heard.single().things.isEmpty())
    }

    @Test
    fun aListenerHearsEverythingThatWasReported() {
        listen()
        assertTrue(ChronicleEvent.hasListeners())
        val other = server.addPlayer("q")
        val character = UUID.randomUUID()
        val start = Instant.parse("2026-11-27T20:00:00.123456Z")
        val before = Instant.now()

        ChronicleEvent.of("sneakylegendarys.orb_placed").actor(player).target(other).targetCharacter(character)
            .thing("orb", 3).thing("plot", " brickton-brickback ").detail("where", "shrine").detail("gold", 50)
            .detail("odds", 0.25).detail("crit", true).detail("big", 5_000_000_000L).fire()
        ChronicleEvent.of("voice.utterance").actorAccount(player.uniqueId).time(start).until(start.plusMillis(1500))
            .text("to the shrine").heardBy(other.uniqueId).at(Location(server.getWorld("world"), 1.5, 64.0, -3.5)).fire()

        val (orb, voice) = heard
        assertEquals("sneakylegendarys.orb_placed", orb.type)
        assertFalse(orb.isAsynchronous)
        assertEquals(ChronicleEvent.Subject(player.uniqueId, null, null), orb.actor)
        assertEquals(
            listOf(ChronicleEvent.Subject(other.uniqueId, null, null), ChronicleEvent.Subject(null, character, null)),
            orb.targets,
        )
        assertEquals(listOf("orb:3", "plot:brickton-brickback"), orb.things.map { it.toString() })
        assertEquals(
            mapOf("where" to "shrine", "gold" to 50L, "odds" to 0.25, "crit" to true, "big" to 5_000_000_000L),
            orb.details,
        )
        assertFalse(orb.start.isBefore(before))
        assertNull(orb.end)
        assertNull(orb.text)
        assertNull(orb.hearers, "the reporter did not say who heard it")
        assertNull(orb.place)

        assertNotEquals(orb.id, voice.id)
        assertEquals(start, voice.start)
        assertEquals(start.plusMillis(1500), voice.end)
        assertEquals("to the shrine", voice.text)
        assertEquals(listOf(other.uniqueId), voice.hearers)
        assertEquals(ChronicleEvent.Place(server.getWorld("world")!!.uid, 1.5, 64.0, -3.5), voice.place)
    }

    @Test
    fun charactersAndNamedActorsCanActAndBeTargets() {
        listen()
        val character = UUID.randomUUID()
        ChronicleEvent.of("sneakyweather.changed").actorNamed("weather").detail("to", "storm").fire()
        ChronicleEvent.of("sneakyplots.member_added").actorCharacter(character).targetNamed("npc:banker").fire()

        assertEquals(ChronicleEvent.Subject(null, null, "weather"), heard[0].actor)
        assertEquals(ChronicleEvent.Subject(null, character, null), heard[1].actor)
        assertEquals(listOf(ChronicleEvent.Subject(null, null, "npc:banker")), heard[1].targets)
    }

    @Test
    fun aReportOffTheServerThreadIsAnAsynchronousEvent() {
        listen()
        val thread = Thread {
            ChronicleEvent.of("chat.message").actorAccount(player.uniqueId).text("hi").heardBy(emptyList()).fire()
        }
        thread.start()
        thread.join()

        val event = heard.single()
        assertTrue(event.isAsynchronous)
        assertEquals(emptyList<UUID>(), event.hearers, "the reporter said nobody heard it")
    }

    @Test
    fun anInvalidTypeDropsTheReportAndInvalidPartsAreLeftOut() {
        listen()
        for (type in listOf("orb_placed", "Sneaky.orb", "sneaky.", "sneaky..orb", "a." + "b".repeat(ChronicleEvent.MAX_TYPE))) {
            ChronicleEvent.of(type).actor(player).fire()
        }
        ChronicleEvent.of(null).fire()
        assertTrue(heard.isEmpty())

        ChronicleEvent.of("test.parts").actor(null).actorNamed("The Weather").target(null).targetAccount(null)
            .thing("Orb", "3").thing("orb", "  ").thing(null, "x").thing("orb", "x".repeat(ChronicleEvent.MAX_THING_ID + 1))
            .detail("Bad Key", "v").detail(null, 1).detail("nan", Double.NaN).detail("gone", null as String?)
            .detail("kept", "v").text("   ").heardBy(null as UUID?).at(null).at(null, 0.0, 0.0, 0.0)
            .at(UUID.randomUUID(), Double.NaN, 0.0, 0.0).time(null).fire()

        val event = heard.single()
        assertNull(event.actor)
        assertTrue(event.targets.isEmpty())
        assertTrue(event.things.isEmpty())
        assertEquals(mapOf("kept" to "v"), event.details)
        assertNull(event.text)
        assertEquals(emptyList<UUID>(), event.hearers)
        assertNull(event.place)
    }

    @Test
    fun longValuesAreCutAndCountsAreBounded() {
        listen()
        val builder = ChronicleEvent.of("test.limits").text("t".repeat(ChronicleEvent.MAX_TEXT + 10))
            .detail("long", "d".repeat(ChronicleEvent.MAX_DETAIL + 10))
        repeat(ChronicleEvent.MAX_ENTRIES + 5) { builder.thing("orb", it).detail("k$it", it).targetAccount(UUID.randomUUID()) }
        builder.thing("orb", 0).target(player).target(player)
        builder.heardBy(List(ChronicleEvent.MAX_HEARERS + 5) { UUID.randomUUID() })
        val start = Instant.now()
        builder.time(start).until(start.minusSeconds(1)).fire()

        val event = heard.single()
        assertEquals(ChronicleEvent.MAX_TEXT, event.text!!.length)
        assertEquals(ChronicleEvent.MAX_DETAIL, (event.details["long"] as String).length)
        assertEquals(ChronicleEvent.MAX_ENTRIES, event.things.size)
        assertEquals(ChronicleEvent.MAX_ENTRIES, event.details.size)
        assertEquals(ChronicleEvent.MAX_ENTRIES, event.targets.size)
        assertEquals(ChronicleEvent.MAX_HEARERS, event.hearers!!.size)
        assertNull(event.end, "an end before the start is left out")
    }

    @Test
    fun aBuilderReportsOnceAndTheEventCannotBeChanged() {
        listen()
        val builder = ChronicleEvent.of("test.once").thing("orb", 1).detail("a", 1).target(player).heardBy(player.uniqueId)
        builder.fire()
        builder.thing("orb", 2).fire()

        val event = heard.single()
        assertEquals(1, event.things.size)
        assertThrows(UnsupportedOperationException::class.java) { (event.things as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (event.targets as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (event.details as MutableMap)["b"] = 2L }
        assertThrows(UnsupportedOperationException::class.java) { (event.hearers as MutableList).clear() }
    }

    @Test
    fun aFailingRecorderDoesNotFailTheReporter() {
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun on(e: ChronicleEvent): Unit = throw IllegalStateException("recorder broke")
        }, owner)
        listen()
        ChronicleEvent.of("test.failing").actor(player).fire()
    }
}
