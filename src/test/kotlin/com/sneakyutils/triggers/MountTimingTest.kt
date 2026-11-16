package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.Location
import org.bukkit.entity.Boat
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.EntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * Riding state and when it changes. Paper fires `EntityMountEvent` and `EntityDismountEvent` before the passenger is
 * attached or detached (MockBukkit does it the other way round, so these tests fire the events in Paper's order
 * themselves). Handlers must see `isInsideVehicle` agree with their cause, a cancelled mount must change nothing, and
 * a mount the service never heard about must still be picked up.
 */
class MountTimingTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var nether: WorldMock
    private lateinit var triggers: Triggers
    private val seen = ArrayList<String>()

    private fun start() {
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
    }

    private fun mock() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        nether = server.addSimpleWorld("nether")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    /** Records each call with whether the player really rides at that moment. */
    private fun witness(name: String) = object : AreaHandler {
        override fun onEnter(player: Player, area: Area, cause: AreaCause) {
            seen += "enter $name $cause riding=${player.isInsideVehicle}"
        }

        override fun onExit(player: Player, area: Area, cause: AreaCause) {
            seen += "exit $name $cause riding=${player.isInsideVehicle}"
        }
    }

    private fun take(): List<String> = ArrayList(seen).also { seen.clear() }

    /** Paper's order: the mount event, then (unless cancelled) the passenger is attached. */
    private fun paperMount(p: PlayerMock, vehicle: Entity) {
        val e = EntityMountEvent(p, vehicle)
        server.pluginManager.callEvent(e)
        if (!e.isCancelled) vehicle.addPassenger(p)
    }

    /** Paper's order: the dismount event while still riding, then the passenger is detached. */
    private fun paperDismount(p: PlayerMock, vehicle: Entity) {
        val e = EntityDismountEvent(p, vehicle)
        server.pluginManager.callEvent(e)
        if (!e.isCancelled) p.leaveVehicle()
    }

    /** A mount without any event (NMS, another plugin's internals): the service can't have heard of it. */
    private fun attachSilently(p: PlayerMock, vehicle: Entity) {
        val f = EntityMock::class.java.getDeclaredField("vehicle")
        f.isAccessible = true
        f.set(p, vehicle)
    }

    @Test
    fun handlersSeeTheRidingStateTheirCauseSays() {
        mock()
        start()
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("only"), AreaOptions.builder().riders(RiderPolicy.ONLY).build())
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("skip"))
        val p = server.addPlayer("p")
        p.teleport(Location(world, 0.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 15.5, 64.0, 2.5))
        assertEquals(listOf("enter skip MOVE riding=false"), take())
        val boat = world.spawn(p.location, Boat::class.java)

        paperMount(p, boat)
        assertEquals(emptyList<String>(), take(), "nothing runs inside the mount event: the mount hasn't happened yet")
        server.scheduler.performOneTick()
        assertEquals(listOf("enter only VEHICLE riding=true"), take())

        p.location = Location(world, 30.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(setOf("exit skip VEHICLE riding=true", "exit only VEHICLE riding=true"), take().toSet())
        p.location = Location(world, 15.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(listOf("enter only VEHICLE riding=true"), take())

        paperDismount(p, boat)
        assertEquals(emptyList<String>(), take(), "nothing runs inside the dismount event either")
        server.scheduler.performOneTick()
        assertEquals(listOf("enter skip MOVE riding=false"), take(), "dismounted inside the SKIP area: entered on foot")
    }

    @Test
    fun aDismountPaperReportsAsATeleportIsAStepOnFoot() {
        // Measured on Paper 26.2: the player's vehicle is already cleared when EntityDismountEvent fires, and Paper
        // then moves the player from the seat to beside the vehicle with a PlayerTeleportEvent (cause DISMOUNT). That
        // reached areas as a TELEPORT exit and was invisible to watches without includeTeleports.
        mock()
        start()
        triggers.area(owner, Area.box(world, 10, 60, 0, 10, 70, 5), witness("only"), AreaOptions.builder().riders(RiderPolicy.ONLY).build())
        val steps = ArrayList<String>()
        val p = server.addPlayer("p")
        p.teleport(Location(world, 12.5, 64.0, 2.5))
        triggers.watchSteps(owner, p, { _, from, to, mounted -> steps += "${from.x}->${to.x} mounted=$mounted" },
            WatchOptions.builder().onFootOnly(false).build())
        val boat = world.spawn(Location(world, 10.5, 64.0, 2.5), Boat::class.java)
        paperMount(p, boat)
        p.location = Location(world, 10.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(listOf("enter only VEHICLE riding=true"), take())
        steps.clear()

        val f = EntityMock::class.java.getDeclaredField("vehicle")
        f.isAccessible = true
        f.set(p, null) // Paper: the vehicle is cleared first ...
        server.pluginManager.callEvent(EntityDismountEvent(p, boat)) // ... then the event ...
        p.teleport(Location(world, 9.5, 64.0, 2.5), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.DISMOUNT) // ... then this
        assertEquals(listOf("exit only MOVE riding=false"), take(), "a step on foot, not a teleport")
        assertEquals(listOf("10->9 mounted=false"), steps, "watches without includeTeleports see it")
        server.scheduler.performOneTick()
        assertEquals(emptyList<String>(), take())
        assertEquals(0, triggers.stats().mountedPlayers)
    }

    @Test
    fun aCancelledMountChangesNothing() {
        mock()
        start()
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("only"), AreaOptions.builder().riders(RiderPolicy.ONLY).build())
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOW)
            fun deny(e: EntityMountEvent) {
                e.isCancelled = true
            }
        }, owner)
        val p = server.addPlayer("p")
        p.teleport(Location(world, 15.5, 64.0, 2.5))
        paperMount(p, world.spawn(p.location, Boat::class.java))
        server.scheduler.performTicks(3)
        assertFalse(p.isInsideVehicle)
        assertEquals(emptyList<String>(), take())
        assertEquals(0, triggers.stats().mountedPlayers)
    }

    @Test
    fun aMountNobodyAnnouncedIsPickedUpOnTeleportAndWorldChange() {
        mock()
        start()
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("only"), AreaOptions.builder().riders(RiderPolicy.ONLY).build())
        triggers.area(owner, Area.box(nether, 10, 60, 0, 20, 70, 5), witness("nether"), AreaOptions.builder().riders(RiderPolicy.ONLY).build())
        val p = server.addPlayer("p")
        p.teleport(Location(world, 0.5, 64.0, 2.5))
        attachSilently(p, world.spawn(p.location, Boat::class.java))
        assertEquals(0, triggers.stats().mountedPlayers, "the service hasn't heard of it")
        p.teleport(Location(world, 15.5, 64.0, 2.5))
        assertEquals(listOf("enter only VEHICLE riding=true"), take(), "the teleport read the riding state: a vehicle move")
        assertEquals(1, triggers.stats().mountedPlayers)
        p.location = Location(world, 40.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(listOf("exit only VEHICLE riding=true"), take(), "and the poll follows it now")

        val q = server.addPlayer("q")
        q.teleport(Location(world, 0.5, 64.0, 2.5))
        attachSilently(q, world.spawn(q.location, Boat::class.java))
        q.location = Location(nether, 15.5, 64.0, 2.5) // no teleport event: only the world change says so
        server.pluginManager.callEvent(PlayerChangedWorldEvent(q, world))
        assertEquals(listOf("enter nether WORLD_CHANGE riding=true"), take())
        assertEquals(2, triggers.stats().mountedPlayers)
    }

    @Test
    fun playersOnTheDeathScreenWhenTheServiceStartsEnterNothingUntilTheyRespawn() {
        // Codex final review: join() created the state alive, so areas registered afterwards took the dead player
        // (handlers ran before the respawn with fireOnRegister; silently otherwise, and the respawn enter never fired).
        mock()
        world.spawnLocation = Location(world, 15.5, 64.0, 2.5)
        val p = server.addPlayer("p")
        p.teleport(Location(world, 15.5, 64.0, 2.5))
        p.health = 0.0
        assertTrue(p.isDead)
        start() // SneakyUtils enables while p is on the death screen
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("announce"), AreaOptions.builder().fireOnRegister(true).build())
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("silent"))
        val timerCalls = ArrayList<String>()
        val all = triggers.everyForPlayers(owner, 1, { timerCalls += it.name }, PlayerScope.ALL_ONLINE)
        server.scheduler.performTicks(3)
        assertEquals(emptyList<String>(), take(), "no handler runs for a dead player")
        assertTrue(all.contains(p))
        assertEquals(emptyList<String>(), timerCalls, "skipDead timers keep a dead player paused from the start")
        p.respawn()
        assertEquals(setOf("enter announce TELEPORT riding=false", "enter silent TELEPORT riding=false"), take().toSet(),
            "the respawn is the first evaluation, so both areas announce it")
        server.scheduler.performOneTick()
        assertEquals(listOf("p"), timerCalls)
        p.disconnect()
        assertEquals(setOf("exit announce QUIT riding=false", "exit silent QUIT riding=false"), take().toSet())
    }

    @Test
    fun playersAlreadyRidingWhenTheServiceStartsArePolled() {
        mock()
        val p = server.addPlayer("p")
        p.teleport(Location(world, 0.5, 64.0, 2.5))
        world.spawn(p.location, Boat::class.java).addPassenger(p) // before SneakyUtils enables (a reload)
        start()
        triggers.area(owner, Area.box(world, 10, 60, 0, 20, 70, 5), witness("toll"), AreaOptions.builder().riders(RiderPolicy.ALLOW).build())
        assertEquals(1, triggers.stats().mountedPlayers)
        p.location = Location(world, 15.5, 64.0, 2.5)
        server.scheduler.performOneTick()
        assertEquals(listOf("enter toll VEHICLE riding=true"), take())
        assertTrue((triggers as TriggerService).core.mountedCount == 1)
    }
}
