package com.sneakyutils.targets

import com.sneakyutils.SneakyUtils

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class VirtualTargetsTest {
    private lateinit var server: ServerMock
    private lateinit var world: World
    private lateinit var owner: Plugin
    private val registered = ArrayList<VirtualTargetProvider>()

    /** One target standing [x] blocks east of the origin; a line east from the origin meets it there. */
    private inner class Lone(val x: Double) : VirtualTargetProvider() {
        var hurt = 0
        var directionLength = 0.0

        override fun damageLine(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Int {
            directionLength = direction.length()
            from.add(100.0, 0.0, 0.0); direction.zero() // a provider may do as it likes with its copies
            return if (length >= x) 1.also { hurt++ } else 0
        }

        override fun firstAlong(from: Location, direction: Vector, length: Double, radius: Double): Location? =
            if (length >= x) Location(world, x, 0.0, 0.0) else null

        override fun damageFirst(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Location? =
            firstAlong(from, direction, length, radius)?.also { hurt++ }

        override fun damageSphere(center: Location, radius: Double, damage: Double, source: Entity?): Int = 2
    }

    private fun <T : VirtualTargetProvider> add(provider: T, by: Plugin = owner): T = provider.also {
        VirtualTargets.register(by, it)
        registered += it
    }

    private fun origin() = Location(world, 0.0, 0.0, 0.0)
    private val east get() = Vector(3.0, 0.0, 0.0) // not a unit vector: the service makes it one

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
    }

    @AfterEach
    fun tearDown() {
        registered.forEach { VirtualTargets.unregister(it) }
        HandlerList.unregisterAll(owner)
        MockBukkit.unmock()
    }

    @Test
    fun withNoProviderEveryCallHitsNothing() {
        assertFalse(VirtualTargets.any())
        assertEquals(0, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, 5.0, null))
        assertNull(VirtualTargets.damageFirst(origin(), east, 50.0, 0.5, 5.0, null))
        assertNull(VirtualTargets.firstAlong(origin(), east, 50.0, 0.5))
        assertEquals(0, VirtualTargets.damageSphere(origin(), 4.0, 5.0, null))
        assertEquals(0, VirtualTargets.damageExplosion(origin(), 4.0, null))
    }

    @Test
    fun aLineHitsEveryProvidersTargetsAndLeavesTheCallersArgumentsAlone() {
        val near = add(Lone(10.0))
        val far = add(Lone(30.0))
        val from = origin()
        val dir = east
        assertTrue(VirtualTargets.any())
        assertEquals(2, VirtualTargets.damageLine(from, dir, 50.0, 0.5, 5.0, null))
        assertEquals(1, VirtualTargets.damageLine(from, dir, 20.0, 0.5, 5.0, null))
        assertEquals(2, near.hurt)
        assertEquals(1, far.hurt)
        assertEquals(1.0, near.directionLength, 1e-9)
        assertEquals(origin(), from)
        assertEquals(east, dir)
        assertEquals(4, VirtualTargets.damageSphere(origin(), 4.0, 5.0, null))
        // a direction with no length, or no length to go: nothing
        assertEquals(0, VirtualTargets.damageLine(from, Vector(), 50.0, 0.5, 5.0, null))
        assertEquals(0, VirtualTargets.damageLine(from, dir, 0.0, 0.5, 5.0, null))
    }

    @Test
    fun callsThatMakeNoSenseHitNothing() {
        val one = add(Lone(10.0))
        for (length in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(0, VirtualTargets.damageLine(origin(), east, length, 0.5, 5.0, null))
            assertNull(VirtualTargets.damageFirst(origin(), east, length, 0.5, 5.0, null))
            assertNull(VirtualTargets.firstAlong(origin(), east, length, 0.5))
        }
        assertEquals(0, VirtualTargets.damageLine(origin(), east, 50.0, Double.NaN, 5.0, null))
        assertEquals(0, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, Double.NaN, null))
        assertEquals(0, VirtualTargets.damageSphere(origin(), Double.POSITIVE_INFINITY, 5.0, null))
        assertEquals(0, VirtualTargets.damageSphere(origin(), 4.0, Double.NaN, null))
        assertEquals(0, VirtualTargets.damageExplosion(origin(), Double.NaN, null))
        assertEquals(0, one.hurt)
    }

    @Test
    fun aFirstHitIsFoundTheSameWayHoweverManyProvidersThereAre() {
        // takes first hits but doesn't say where its targets are: never found, alone or not
        val mute = add(object : VirtualTargetProvider() {
            override fun damageFirst(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Location? =
                Location(world, 5.0, 0.0, 0.0)
        })
        assertNull(VirtualTargets.damageFirst(origin(), east, 50.0, 0.5, 5.0, null))
        // a provider that only takes blasts beside one that takes first hits: the latter is still found
        add(object : VirtualTargetProvider() {
            override fun damageSphere(center: Location, radius: Double, damage: Double, source: Entity?): Int = 1
        })
        val shot = add(Lone(10.0))
        assertEquals(10.0, VirtualTargets.damageFirst(origin(), east, 50.0, 0.5, 5.0, null)!!.x)
        assertEquals(1, shot.hurt)
        VirtualTargets.unregister(mute)
    }

    @Test
    fun aProviderRemovedByAnEarlierProvidersHitIsNotCalled() {
        val gone = MockBukkit.createMockPlugin("Gone")
        lateinit var second: Lone
        // the first provider's hit ends the second (as a listener of its damage event might)
        add(object : VirtualTargetProvider() {
            override fun damageLine(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Int {
                VirtualTargets.unregister(second)
                server.pluginManager.disablePlugin(gone)
                return 1
            }
        })
        second = add(Lone(10.0))
        val third = add(Lone(10.0), gone)
        assertEquals(1, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, 5.0, null))
        assertEquals(0, second.hurt)
        assertEquals(0, third.hurt)
    }

    @Test
    fun aDisabledOwnersProvidersGoAtOnceAndItCannotRegisterWhileOff() {
        val gone = MockBukkit.createMockPlugin("Gone")
        val theirs = add(Lone(10.0), gone)
        server.pluginManager.disablePlugin(gone)
        assertFalse(VirtualTargets.unregister(theirs), "dropped by the disable itself, before any shot")
        VirtualTargets.register(gone, theirs)
        assertFalse(VirtualTargets.any())
    }

    @Test
    fun theFirstHitIsTheNearestAcrossProviders() {
        val far = add(Lone(30.0))
        val near = add(Lone(10.0))
        assertEquals(10.0, VirtualTargets.firstAlong(origin(), east, 50.0, 0.5)!!.x)
        assertEquals(10.0, VirtualTargets.damageFirst(origin(), east, 50.0, 0.5, 5.0, null)!!.x)
        assertEquals(1, near.hurt)
        assertEquals(0, far.hurt)
        assertNull(VirtualTargets.damageFirst(origin(), east, 5.0, 0.5, 5.0, null))
    }

    @Test
    fun aProviderThatFailsOrWhoseOwnerIsDisabledDoesNotStopTheOthers() {
        add(object : VirtualTargetProvider() {
            override fun damageLine(from: Location, direction: Vector, length: Double, radius: Double, damage: Double, source: Entity?): Int =
                throw IllegalStateException("broken")
        })
        val gone = MockBukkit.createMockPlugin("Gone")
        val theirs = add(Lone(10.0), gone)
        val ours = add(Lone(10.0))
        assertEquals(2, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, 5.0, null))
        server.pluginManager.disablePlugin(gone)
        assertEquals(1, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, 5.0, null))
        assertEquals(1, theirs.hurt)
        assertEquals(2, ours.hurt)
        assertFalse(VirtualTargets.unregister(theirs), "dropped with its owner")
        // what a provider doesn't override hits nothing
        assertEquals(0, VirtualTargets.damageExplosion(origin(), 4.0, null))
    }

    @Test
    fun registeringTwiceCountsOnce() {
        val one = add(Lone(10.0))
        VirtualTargets.register(owner, one)
        assertEquals(1, VirtualTargets.damageLine(origin(), east, 50.0, 0.5, 5.0, null))
        assertTrue(VirtualTargets.unregister(one))
        assertFalse(VirtualTargets.any())
    }

    @Test
    fun theDamageEventLetsAListenerChangeOrCancelAHit() {
        fun fire(damage: Double) = VirtualTargetDamageEvent.fire(owner, origin(), "goblin", damage, 10.0, null, null)
        assertEquals(6.0, fire(6.0), "nobody listens: as asked")
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun on(e: VirtualTargetDamageEvent) {
                assertEquals("goblin", e.kind)
                assertEquals(10.0, e.health)
                if (e.damage > 50) e.isCancelled = true else e.damage = 99.0
            }
        }, owner)
        assertEquals(99.0, fire(6.0))
        assertTrue(fire(60.0).isNaN(), "cancelled")
    }
}
