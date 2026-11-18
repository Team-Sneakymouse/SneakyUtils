package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggersCommand
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
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

/**
 * [Triggers.occupancy] through real Bukkit events in MockBukkit: counts and first-in / last-out transitions for every
 * way in and out, the defaults (riders count, spectators don't, the dead stay), lifecycle and re-anchoring. Engine
 * details: engine/OccupancyEngineTest.
 */
class OccupancyBukkitTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var nether: WorldMock
    private lateinit var triggers: Triggers
    private val log = ArrayList<String>()

    /** Logs every call with the count the handle shows during it. */
    private fun recorder(name: String) = object : OccupancyHandler {
        override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
            log += "first $name ${player.name} $cause ${occupancy.count}"
        }

        override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
            log += "last $name ${player.name} $cause ${occupancy.count}"
        }

        override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
            log += "enter $name ${player.name} $cause ${occupancy.count}"
        }

        override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
            log += "exit $name ${player.name} $cause ${occupancy.count}"
        }
    }

    private fun take(): List<String> = ArrayList(log).also { log.clear() }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        nether = server.addSimpleWorld("nether")
        world.spawnLocation = Location(world, 1000.5, 64.0, 1000.5) // joins and respawns land far from every box
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun player(name: String, x: Double, y: Double, z: Double, w: WorldMock = world): PlayerMock {
        val p = server.addPlayer(name)
        p.teleport(Location(w, x, y, z))
        return p
    }

    private fun box(x1: Int, z1: Int, x2: Int, z2: Int, w: WorldMock = world) = Area.box(w, x1, 60, z1, x2, 70, z2)

    @Test
    fun walkingInAndOutCountsPlayersAndFiresOnlyRealTransitions() {
        val occ = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"))
        val a = player("a", 20.5, 64.0, 5.5)
        val b = player("b", 20.5, 64.0, 6.5)
        assertEquals(0, occ.count)
        assertFalse(occ.isOccupied)

        a.simulatePlayerMove(Location(world, 5.5, 64.0, 5.5))
        assertEquals(listOf("first O a MOVE 1", "enter O a MOVE 1"), take())
        b.simulatePlayerMove(Location(world, 6.5, 64.0, 6.5))
        assertEquals(listOf("enter O b MOVE 2"), take(), "a second player is no first-in")
        assertEquals(2, occ.count)
        assertTrue(occ.isOccupied)
        assertTrue(occ.contains(a))
        val snapshot = occ.players
        assertEquals(listOf("a", "b"), snapshot.map { it.name }, "in the order they entered")
        assertThrows(UnsupportedOperationException::class.java) { (snapshot as MutableList<Player>).clear() }

        a.simulatePlayerMove(Location(world, 20.5, 64.0, 5.5))
        assertEquals(listOf("exit O a MOVE 1"), take())
        assertEquals(listOf("a", "b"), snapshot.map { it.name }, "a snapshot, not a live view")
        assertFalse(occ.contains(a))
        b.simulatePlayerMove(Location(world, 20.5, 64.0, 6.5))
        assertEquals(listOf("exit O b MOVE 0", "last O b MOVE 0"), take())
        assertEquals(0, occ.count)
        assertTrue(occ.players.isEmpty())
    }

    @Test
    fun teleportWorldChangeJoinAndQuit() {
        val occ = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"))
        val p = player("p", 20.5, 64.0, 5.5)
        p.teleport(Location(world, 5.5, 64.0, 5.5))
        assertEquals(listOf("first O p TELEPORT 1", "enter O p TELEPORT 1"), take())
        p.teleport(Location(nether, 5.5, 64.0, 5.5))
        assertEquals(listOf("exit O p WORLD_CHANGE 0", "last O p WORLD_CHANGE 0"), take())

        world.spawnLocation = Location(world, 5.5, 64.0, 5.5)
        val q = server.addPlayer("q")
        assertEquals(listOf("first O q JOIN 1", "enter O q JOIN 1"), take())
        q.disconnect()
        assertEquals(listOf("exit O q QUIT 0", "last O q QUIT 0"), take())
        assertEquals(0, occ.count)
    }

    @Test
    fun deadPlayersStayCountedUntilTheyRespawnElsewhere() {
        val occ = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"))
        val p = player("p", 5.5, 64.0, 5.5)
        take()
        p.health = 0.0
        assertTrue(p.isDead)
        assertEquals(emptyList<String>(), take(), "death is no exit")
        assertEquals(1, occ.count)
        assertTrue(occ.contains(p))
        p.respawn() // at the far world spawn
        assertEquals(listOf("exit O p TELEPORT 0", "last O p TELEPORT 0"), take())

        p.teleport(Location(world, 5.5, 64.0, 5.5))
        take()
        world.spawnLocation = Location(world, 6.5, 64.0, 6.5)
        p.health = 0.0
        p.respawn()
        assertEquals(emptyList<String>(), take(), "respawning inside: no calls, still counted")
        assertEquals(1, occ.count)

        p.health = 0.0
        p.disconnect()
        assertEquals(listOf("exit O p QUIT 0", "last O p QUIT 0"), take(), "a dead player who quits leaves")
    }

    @Test
    fun withCountDeadOffDeathIsAnExit() {
        triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"), OccupancyOptions.builder().countDead(false).build())
        val p = player("p", 5.5, 64.0, 5.5)
        take()
        p.health = 0.0
        assertEquals(listOf("exit O p DEATH 0", "last O p DEATH 0"), take())
    }

    @Test
    fun ridersCountByDefault() {
        val occ = triggers.occupancy(owner, box(10, 0, 20, 5), recorder("O"))
        val skip = triggers.occupancy(owner, box(10, 0, 20, 5), recorder("S"),
            OccupancyOptions.builder().riders(RiderPolicy.SKIP).build())
        val p = player("p", 0.5, 64.0, 2.5)
        world.spawn(Location(world, 0.5, 64.0, 2.5), ArmorStand::class.java).addPassenger(p)
        p.location = Location(world, 15.5, 64.0, 2.5) // carried by the vehicle: only the poll sees it
        server.scheduler.performOneTick()
        assertEquals(listOf("first O p VEHICLE 1", "enter O p VEHICLE 1"), take())
        assertEquals(1, occ.count)
        assertEquals(0, skip.count)
    }

    @Test
    fun spectatorsDontCountAndSwitchingModesInsideLeavesAndReenters() {
        val occ = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"))
        val all = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("S"),
            OccupancyOptions.builder().includeSpectators(true).build())
        val p = player("p", 5.5, 64.0, 5.5)
        assertEquals(listOf("first O p TELEPORT 1", "enter O p TELEPORT 1", "first S p TELEPORT 1", "enter S p TELEPORT 1"), take())

        p.gameMode = GameMode.SPECTATOR
        assertEquals(listOf("exit O p GAME_MODE 0", "last O p GAME_MODE 0"), take())
        assertEquals(1, all.count, "includeSpectators keeps them")
        p.simulatePlayerMove(Location(world, 6.5, 64.0, 6.5))
        assertEquals(emptyList<String>(), take())
        p.gameMode = GameMode.CREATIVE
        assertEquals(listOf("first O p GAME_MODE 1", "enter O p GAME_MODE 1"), take(), "switching back inside re-enters")

        val q = player("q", 20.5, 64.0, 5.5)
        q.gameMode = GameMode.SPECTATOR
        q.simulatePlayerMove(Location(world, 5.5, 64.0, 5.5))
        assertEquals(listOf("enter S q MOVE 2"), take(), "a spectator walking in counts only where included")
        assertEquals(1, occ.count)
    }

    @Test
    fun overlappingOccupanciesAreIndependent() {
        val big = triggers.occupancy(owner, box(0, 0, 20, 20), recorder("big"))
        val small = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("small"))
        val p = player("p", 10.5, 64.0, 10.5)
        val q = player("q", 2.5, 64.0, 2.5)
        assertEquals(
            listOf("first big p TELEPORT 1", "enter big p TELEPORT 1", "enter big q TELEPORT 2", "first small q TELEPORT 1",
                "enter small q TELEPORT 1"),
            take(),
        )
        q.simulatePlayerMove(Location(world, 10.5, 64.0, 2.5))
        assertEquals(listOf("exit small q MOVE 0", "last small q MOVE 0"), take(), "the big one doesn't notice")
        small.close()
        assertEquals(2, big.count)
        assertEquals(emptyList<String>(), take())
        assertTrue(big.contains(p) && big.contains(q))
    }

    @Test
    fun registeringWithPlayersInsideEntersThemBeforeItReturns() {
        player("a", 2.5, 64.0, 2.5)
        player("b", 3.5, 64.0, 2.5)
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("O"))
        assertEquals(listOf("first O a REGISTER 1", "enter O a REGISTER 1", "enter O b REGISTER 2"), take())
        assertEquals(2, occ.count)
    }

    @Test
    fun closeTakesEveryoneOutOnceAndIsIdempotent() {
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("O"))
        player("a", 2.5, 64.0, 2.5)
        player("b", 3.5, 64.0, 2.5)
        take()
        assertEquals(1, triggers.stats().count("occupancy"))
        occ.close()
        assertEquals(listOf("exit O a UNREGISTER 1", "exit O b UNREGISTER 0", "last O b UNREGISTER 0"), take())
        assertFalse(occ.active)
        assertEquals(0, occ.count)
        occ.close()
        assertEquals(emptyList<String>(), take())
        assertThrows(IllegalStateException::class.java) { occ.moveTo(box(10, 10, 15, 15)) }
        assertEquals(0, triggers.stats().count("occupancy"))
        assertEquals(0, triggers.stats().areaChunks)
    }

    @Test
    fun ownerDisableClosesSilentlyAndEmptiesTheHandle() {
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("O"))
        player("a", 2.5, 64.0, 2.5)
        take()
        server.pluginManager.disablePlugin(owner)
        assertEquals(emptyList<String>(), take(), "nothing reaches a disabling owner")
        assertFalse(occ.active)
        assertEquals(0, occ.count)
        assertTrue(occ.players.isEmpty())
        assertEquals(0, triggers.stats().areaChunks)
    }

    @Test
    fun exitsOfAnExplicitCloseStillEmptyTheHandleWhenTheOwnerDisablesBeforeThem() {
        // Closed from another plugin's handler (its exits queue behind), then its owner disables before they arrive:
        // the handle still empties, and nothing is called in the disabled owner.
        val other = MockBukkit.createMockPlugin("Other")
        val occ = triggers.occupancy(other, box(0, 0, 5, 5), recorder("O"))
        triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { _, _, _ ->
            occ.close()
            server.pluginManager.disablePlugin(other)
        })
        player("b", 2.5, 64.0, 2.5)
        assertEquals(listOf("first O b TELEPORT 1", "enter O b TELEPORT 1"), take())
        assertFalse(occ.active)
        assertEquals(0, occ.count)
        assertTrue(occ.players.isEmpty())
    }

    @Test
    fun worldUnloadClosesIt() {
        val gone = server.addSimpleWorld("instance")
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5, gone), recorder("I"))
        assertTrue(server.unloadWorld(gone, false))
        assertFalse(occ.active)
    }

    @Test
    fun moveToKeepsTheOverlapAndOnlyRealTransitionsFire() {
        val occ = triggers.occupancy(owner, box(0, 0, 10, 10), recorder("O"))
        val a = player("a", 2.5, 64.0, 5.5)   // old only
        player("b", 8.5, 64.0, 5.5)           // both
        player("c", 13.5, 64.0, 5.5)          // new only
        take()
        val next = box(5, 0, 15, 10)
        occ.moveTo(next)
        assertEquals(listOf("enter O c AREA_MOVED 3", "exit O a AREA_MOVED 2"), take(), "b stays; no first-in or last-out")
        assertTrue(occ.area === next)

        // One player swapped for another: enters come first, so the count never touches 0.
        occ.moveTo(box(5, 0, 10, 10))
        take()
        assertEquals(listOf("b"), occ.players.map { it.name })
        occ.moveTo(box(0, 0, 4, 10))
        assertEquals(listOf("enter O a AREA_MOVED 2", "exit O b AREA_MOVED 1"), take())

        occ.moveTo(box(100, 0, 110, 10))
        assertEquals(listOf("exit O a AREA_MOVED 0", "last O a AREA_MOVED 0"), take())
        a.teleport(Location(world, 99.5, 64.0, 5.5))
        a.simulatePlayerMove(Location(world, 105.5, 64.0, 5.5))
        assertEquals(listOf("first O a MOVE 1", "enter O a MOVE 1"), take(), "steps use the new area")

        val n = player("n", 105.5, 64.0, 5.5, nether)
        take()
        occ.moveTo(box(100, 0, 110, 10, nether))
        assertEquals(listOf("enter O n AREA_MOVED 2", "exit O a AREA_MOVED 1"), take(), "moves across worlds")
        assertTrue(occ.contains(n))
    }

    @Test
    fun translatingEveryTickPassesOverAStandingPlayerOnce() {
        val occ = triggers.occupancy(owner, box(0, 0, 4, 4), recorder("O"))
        player("p", 40.5, 64.0, 2.5)
        triggers.every(owner, 1) { occ.translate(1, 0, 0) }
        server.scheduler.performTicks(100)
        assertEquals(listOf("first O p AREA_MOVED 1", "enter O p AREA_MOVED 1", "exit O p AREA_MOVED 0", "last O p AREA_MOVED 0"), take())
        assertTrue(occ.area.minX >= 90)
        assertEquals(1, triggers.stats().areaChunks, "the index holds only the station's current chunk")
    }

    @Test
    fun handlersMayMoveAndCloseTheOccupancyAndTheirEffectsComeAfter() {
        lateinit var occ: Occupancy
        occ = triggers.occupancy(owner, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "first ${player.name} $cause"
                occupancy.moveTo(box(100, 0, 105, 5)) // flies off as soon as someone boards
                log += "count still ${occupancy.count}"
            }

            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter ${player.name} $cause"
            }

            override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "last ${player.name} $cause"
                occupancy.close()
            }
        })
        val p = player("p", 20.5, 64.0, 2.5)
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("first p MOVE", "count still 1", "enter p MOVE", "last p AREA_MOVED"), take())
        assertFalse(occ.active)
    }

    @Test
    fun shapesDecideMembershipAtTheirBoundaries() {
        // A ball of radius 3 around (0.5, 64.0, 0.5) and a cylinder of radius 3 standing on y 64, 2 high, elsewhere.
        val ball = triggers.occupancy(owner, Area.sphere(world, 0.5, 64.0, 0.5, 3.0), recorder("ball"))
        val can = triggers.occupancy(owner, Area.cylinder(Location(world, 100.5, 64.0, 0.5), 3.0, 2.0), recorder("can"))
        val p = player("p", 3.5, 64.0, 0.5)        // distance 3: on the surface, inside
        val q = player("q", 3.5, 64.0, 2.5)        // in the box, but sqrt(13) from the centre
        assertTrue(ball.contains(p))
        assertFalse(ball.contains(q))
        q.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5)) // sqrt(8)
        assertTrue(ball.contains(q))
        p.simulatePlayerMove(Location(world, 0.5, 67.0, 0.5)) // straight up 3: still on the surface
        assertTrue(ball.contains(p))
        p.simulatePlayerMove(Location(world, 0.5, 68.0, 0.5))
        assertFalse(ball.contains(p))

        p.teleport(Location(world, 103.5, 64.0, 0.5)) // the cylinder's edge, at its base
        assertTrue(can.contains(p))
        p.simulatePlayerMove(Location(world, 103.5, 66.0, 0.5)) // its top
        assertTrue(can.contains(p))
        p.simulatePlayerMove(Location(world, 103.5, 67.0, 0.5))
        assertFalse(can.contains(p))
        p.simulatePlayerMove(Location(world, 100.5, 63.0, 0.5)) // under its base
        assertFalse(can.contains(p))
        p.simulatePlayerMove(Location(world, 100.5, 65.0, 3.5))
        assertTrue(can.contains(p))
        p.simulatePlayerMove(Location(world, 102.5, 65.0, 2.5)) // sqrt(8) from the axis
        assertTrue(can.contains(p))
        p.simulatePlayerMove(Location(world, 103.5, 65.0, 2.5)) // sqrt(13)
        assertFalse(can.contains(p))
    }

    @Test
    fun aHandlerLoopOverTheDrainLimitLeavesCountsMatchingWhereThePlayerIs() {
        // Review finding 1: the limit drops queued enters and exits, which the engine had already applied. The handle
        // is brought back in line without calling handlers.
        val service = triggers as TriggerService
        service.plugin.logger.level = java.util.logging.Level.OFF // the loop's log line
        triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { p, _, _ -> p.teleport(Location(world, 102.5, 64.0, 2.5)) })
        triggers.area(owner, box(100, 0, 105, 5), AreaHandler.onEnter { p, _, _ -> p.teleport(Location(world, 2.5, 64.0, 2.5)) })
        val here = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("here"))
        val there = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("there"))
        val p = player("p", 50.5, 64.0, 2.5)
        val dropped = service.counters()["queue.dropped"]!!
        p.simulatePlayerMove(Location(world, 40.5, 64.0, 2.5))
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertTrue(service.counters()["queue.dropped"]!! > dropped, "the loop was cut")
        val s = service.core.state(p.uniqueId)!!
        assertEquals(s.isMember((here as com.sneakyutils.triggers.service.OccupancySub).reg), here.contains(p))
        assertEquals(s.isMember((there as com.sneakyutils.triggers.service.OccupancySub).reg), there.contains(p))
        assertEquals(1, here.count + there.count, "inside exactly one of them")
        take()
        p.disconnect()
        assertEquals(0, here.count)
        assertEquals(0, there.count)
        assertEquals(1, take().count { it.startsWith("last ") }, "the quit takes them out of the one they are in")
    }

    @Test
    fun movingOntoAShapeUsesWherePlayersAreNowWithinTheirBlock() {
        // Review finding 2: moves inside a block aren't reported, so the engine's exact position can be stale.
        val occ = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("O"))
        val p = player("p", 0.1, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // same block: no step
        occ.moveTo(Area.sphere(world, 0.8, 64.0, 0.5, 0.2))
        assertTrue(occ.contains(p), "tested at 0.8, not at the last step's 0.1")
        p.simulatePlayerMove(Location(world, 0.3, 64.0, 0.5))
        occ.moveTo(Area.sphere(world, 0.8, 64.0, 0.5, 0.25))
        assertFalse(occ.contains(p), "and out again at 0.3")
    }

    @Test
    fun registeringAShapeTestsPlayersWhereTheyAreNow() {
        // Second review, finding 1.
        val p = player("p", 0.1, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // same block: no step
        val q = player("q", 5.8, 64.0, 0.5)
        q.simulatePlayerMove(Location(world, 5.1, 64.0, 0.5))
        val here = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("here"))
        val gone = triggers.occupancy(owner, Area.sphere(world, 5.8, 64.0, 0.5, 0.2), recorder("gone"))
        assertTrue(here.contains(p), "p stands at 0.8 now")
        assertFalse(gone.contains(q), "q walked off to 5.1 within the block")
        assertEquals(listOf("first here p REGISTER 1", "enter here p REGISTER 1"), take())
    }

    @Test
    fun leavingSpectatorTestsWhereThePlayerIsNow() {
        // Second review, finding 2.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("O"))
        val p = player("p", 20.5, 64.0, 0.5)
        p.gameMode = GameMode.SPECTATOR
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.5)) // a step into block 0, outside the ball
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // into the ball, same block: no step
        assertEquals(emptyList<String>(), take())
        p.gameMode = GameMode.SURVIVAL
        assertEquals(listOf("first O p GAME_MODE 1", "enter O p GAME_MODE 1"), take())
        assertTrue(occ.contains(p))
    }

    @Test
    fun movingToTheSameAreaReEvaluates() {
        // Second review, finding 3.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("O"))
        val p = player("p", 0.8, 64.0, 0.5)
        take()
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.5)) // out of the ball, same block: no step
        val chunks = triggers.stats().areaChunks
        occ.moveTo(occ.area)
        assertEquals(listOf("exit O p AREA_MOVED 0", "last O p AREA_MOVED 0"), take())
        assertEquals(chunks, triggers.stats().areaChunks)
        occ.translate(0, 0, 0)
        assertEquals(emptyList<String>(), take())
    }

    @Test
    fun aSameBlockTeleportFromAMoveHandlerIsSeenByAMoveTo() {
        // Second review, finding 4: the outer move is still being handled, so the teleport's exact destination (in
        // the block the move reached) must be what the engine has.
        val occ = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("O"))
        triggers.area(owner, box(10, 0, 10, 0), AreaHandler.onEnter { pl, _, cause ->
            if (cause != AreaCause.MOVE) return@onEnter
            pl.teleport(Location(world, 10.8, 64.0, 0.5))
            occ.moveTo(Area.sphere(world, 10.8, 64.0, 0.5, 0.2))
        })
        val p = player("p", 20.5, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 10.1, 64.0, 0.5))
        assertTrue(occ.contains(p))
    }

    @Test
    fun aRidingChangeTestsWhereThePlayerIsNow() {
        // Riders don't count here (SKIP): dismounting inside the ball enters it on foot, tested where they got off.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("O"),
            OccupancyOptions.builder().riders(RiderPolicy.SKIP).build())
        val p = player("p", 0.1, 64.0, 0.5)
        world.spawn(Location(world, 0.1, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick()
        p.location = Location(world, 0.8, 64.0, 0.5)
        p.leaveVehicle()
        server.scheduler.performOneTick()
        assertEquals(listOf("first O p MOVE 1", "enter O p MOVE 1"), take())
        assertTrue(occ.contains(p))
    }

    @Test
    fun mountingTestsWhereThePlayerIsNow() {
        // Only riders count here (ONLY): mounting inside the ball enters it, tested where the player mounted.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("O"),
            OccupancyOptions.builder().riders(RiderPolicy.ONLY).build())
        val p = player("p", 0.1, 64.0, 0.5)
        p.location = Location(world, 0.8, 64.0, 0.5) // walked within the block: no step
        world.spawn(Location(world, 0.8, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick() // the mount applies on the next tick
        assertEquals(listOf("first O p VEHICLE 1", "enter O p VEHICLE 1"), take())
        assertTrue(occ.contains(p))
    }

    /** A custom disc area around (0.1, 0.1) inside block 0,64,0 and a pass-through gate in block 1,64,0. */
    private fun discAndGate(seen: MutableList<String>) {
        triggers.area(owner, Area.custom(world, 0, 64, 0, 0, 64, 0) { x, _, z -> (x - 0.1) * (x - 0.1) + (z - 0.1) * (z - 0.1) <= 0.09 },
            AreaHandler.of({ _, _, c -> seen += "enter disc $c" }, { _, _, c -> seen += "exit disc $c" }),
            AreaOptions.builder().riders(RiderPolicy.ALLOW).includeSpectators(true).build())
        triggers.area(owner, Area.box(world, 1, 64, 0, 1, 64, 0),
            AreaHandler.of({ _, _, c -> seen += "enter gate $c" }, { _, _, c -> seen += "exit gate $c" }),
            AreaOptions.builder().passThrough(true).build())
    }

    /**
     * Third review, findings 1 and 2: with a shaped occupancy registered, moved and refreshed around the player, an
     * ordinary custom area and a pass-through gate get exactly the calls they get without one ([withOccupancy] off is
     * origin/main's behaviour): no exit from the disc on a mount or game mode change, and the gate still crossed.
     */
    private fun ordinaryAreaScript(withOccupancy: Boolean): List<String> {
        val seen = ArrayList<String>()
        discAndGate(seen)
        val p = player("p", 0.1, 64.0, 0.1)
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.9)) // within the block: out of the disc, unreported
        val occ = if (withOccupancy) triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.9, 0.2), recorder("O")) else null
        if (occ != null) assertTrue(occ.contains(p), "the occupancy tests where the player is now")
        p.gameMode = GameMode.SPECTATOR
        occ?.moveTo(Area.sphere(world, 0.1, 64.0, 0.9, 0.3))
        p.gameMode = GameMode.SURVIVAL
        val seat = world.spawn(Location(world, 0.1, 64.0, 0.9), ArmorStand::class.java)
        seat.addPassenger(p)
        server.scheduler.performOneTick()
        occ?.moveTo(occ.area)
        p.leaveVehicle()
        server.scheduler.performOneTick()
        seen += "-- step"
        p.simulatePlayerMove(Location(world, 2.5, 64.0, 1.5))
        return seen
    }

    @Test
    fun ordinaryAreasBehaveAsWithoutOccupancies() {
        assertEquals(
            listOf("enter disc TELEPORT", "-- step", "exit disc MOVE", "enter gate MOVE", "exit gate MOVE"),
            ordinaryAreaScript(withOccupancy = false),
            "origin/main: the disc keeps its member until the next step, whose path starts at the last reported position",
        )
    }

    @Test
    fun ordinaryAreasBehaveTheSameWithAnOccupancyRefreshingAroundThem() {
        assertEquals(
            listOf("enter disc TELEPORT", "-- step", "exit disc MOVE", "enter gate MOVE", "exit gate MOVE"),
            ordinaryAreaScript(withOccupancy = true),
        )
        assertTrue(take().any { it.startsWith("first O p REGISTER") })
    }

    @Test
    fun aCountedMemberWhoWalkedOutWithinTheBlockLeavesWithTheCauseOfWhatFoundThem() {
        // Third review, finding 3.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("O"))
        val dead = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("D"),
            OccupancyOptions.builder().countDead(false).build())
        val p = player("p", 0.1, 64.0, 0.5)
        take()
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // out of the ball, same block
        p.gameMode = GameMode.SPECTATOR
        assertEquals(listOf("exit O p GAME_MODE 0", "last O p GAME_MODE 0", "exit D p GAME_MODE 0", "last D p GAME_MODE 0"), take())
        assertEquals(0, occ.count)

        p.gameMode = GameMode.SURVIVAL
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.5))
        occ.moveTo(occ.area)
        dead.moveTo(dead.area)
        take()
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5))
        world.spawn(Location(world, 0.8, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick()
        assertEquals(listOf("exit O p MOVE 0", "last O p MOVE 0", "exit D p MOVE 0", "last D p MOVE 0"), take(),
            "they walked out before mounting")

        p.leaveVehicle()
        server.scheduler.performOneTick()
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.5))
        dead.moveTo(dead.area)
        take()
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5))
        p.health = 0.0
        assertEquals(listOf("exit D p DEATH 0", "last D p DEATH 0"), take(), "a counted member who dies leaves with DEATH")
    }

    @Test
    fun theResyncAfterANestedTeleportReEvaluatesShapedOccupancies() {
        // Fourth review, finding 1: a handler of a teleport teleports the player again within the block and moves the
        // occupancy onto that spot; Bukkit then applies the outer destination. The next tick's resync corrects the
        // exact position in the same block, and the occupancy must follow.
        val occ = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("O"))
        triggers.area(owner, box(0, 0, 0, 0), AreaHandler.onEnter { pl, _, cause ->
            if (cause != AreaCause.TELEPORT) return@onEnter
            pl.teleport(Location(world, 0.8, 64.0, 0.5))
            occ.moveTo(Area.sphere(world, 0.8, 64.0, 0.5, 0.2))
        })
        val p = player("p", 0.1, 64.0, 0.5)
        assertEquals(0.1, p.location.x, 1e-9, "the outer teleport won")
        assertTrue(occ.contains(p), "counted at the inner destination, for now")
        take()
        server.scheduler.performOneTick()
        assertEquals(listOf("exit O p TELEPORT 0", "last O p TELEPORT 0"), take())
        assertFalse(occ.contains(p))
    }

    @Test
    fun closingThenShuttingSneakyUtilsDownFromOneCallbackEmptiesTheHandle() {
        // Fourth review, finding 2: the close's exits are queued behind the callback and discarded by the shutdown.
        lateinit var occ: Occupancy
        occ = triggers.occupancy(owner, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                occupancy.close()
                server.pluginManager.disablePlugin(server.pluginManager.getPlugin("SneakyUtils")!!)
            }
        })
        player("p", 2.5, 64.0, 2.5)
        assertFalse(occ.active)
        assertEquals(0, occ.count)
        assertTrue(occ.players.isEmpty())
    }

    @Test
    fun anOccupancyClosedBeforeItsQueuedEnterStillEndsEmptyAfterAShutdown() {
        // Fifth review, finding 1: an area registered first closes the occupancy while the occupancy's enter is still
        // queued (its count is 0 at the close); that enter is delivered, and its callback shuts SneakyUtils down.
        lateinit var occ: Occupancy
        triggers.area(owner, box(0, 0, 5, 5), AreaHandler.onEnter { _, _, _ -> occ.close() })
        occ = triggers.occupancy(owner, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "first ${occupancy.count}"
                server.pluginManager.disablePlugin(server.pluginManager.getPlugin("SneakyUtils")!!)
            }

            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter"
            }
        })
        player("p", 2.5, 64.0, 2.5)
        assertEquals(listOf("first 1"), take(), "delivered after the close; nothing more once SneakyUtils is down")
        assertEquals(0, occ.count)
        assertTrue(occ.players.isEmpty())
    }

    @Test
    fun aTeleportToTheSpotAlreadyStoredStillReTestsShapedOccupancies() {
        // Fifth review, finding 2: registering another shape refreshed the exact position without testing the first.
        val first = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("A"))
        val p = player("p", 0.1, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // out of A within the block: still counted
        val second = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("B"))
        assertTrue(first.contains(p) && second.contains(p))
        take()
        p.teleport(Location(world, 0.8, 64.0, 0.5, 90f, 0f)) // the same spot, another way to face
        assertEquals(listOf("exit A p TELEPORT 0", "last A p TELEPORT 0"), take())
        assertTrue(second.contains(p))
    }

    @Test
    fun aGameModeChangeThatIsNotAboutSpectatorStillReTestsShapes() {
        // Sixth review, finding 1: survival to creative. Ordinary areas hear nothing of it (as on origin/main).
        val seen = ArrayList<String>()
        triggers.area(owner, Area.custom(world, 0, 64, 0, 0, 64, 0) { x, _, _ -> x < 0.5 },
            AreaHandler.of({ _, _, c -> seen += "enter disc $c" }, { _, _, c -> seen += "exit disc $c" }))
        val near = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("near"))
        val far = triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("far"))
        val p = player("p", 0.1, 64.0, 0.5)
        take()
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // out of near, into far, within the block
        assertEquals(emptyList<String>(), take())
        p.gameMode = GameMode.CREATIVE
        assertEquals(listOf("exit near p MOVE 0", "last near p MOVE 0", "first far p MOVE 1", "enter far p MOVE 1"), take(),
            "they moved; the mode change only found it")
        assertFalse(near.contains(p))
        assertTrue(far.contains(p))
        assertEquals(listOf("enter disc TELEPORT"), seen, "the ordinary area keeps its member until the next step")
        p.gameMode = GameMode.ADVENTURE
        assertEquals(emptyList<String>(), take(), "nothing changed, nothing called")
    }

    @Test
    fun turningSpectatorIsNotTheCauseForAnOccupancyThatIncludesSpectators() {
        // Seventh review, finding 1.
        triggers.occupancy(owner, Area.sphere(world, 0.8, 64.0, 0.5, 0.2), recorder("S"),
            OccupancyOptions.builder().includeSpectators(true).build())
        val p = player("p", 0.1, 64.0, 0.5)
        p.simulatePlayerMove(Location(world, 0.8, 64.0, 0.5)) // into the ball within the block
        p.gameMode = GameMode.SPECTATOR
        assertEquals(listOf("first S p MOVE 1", "enter S p MOVE 1"), take(), "they walked in; the switch only found it")
    }

    @Test
    fun movingAShapeAwayTakesOutMembersOutsideItsNewBox() {
        // Seventh review, finding 3: exact positions are refreshed only for players in the destination box; members
        // elsewhere (here: far away, and in another world) leave on the block test alone.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.5, 64.0, 0.5, 3.0), recorder("O"))
        val a = player("a", 0.5, 64.0, 0.5)
        val b = player("b", 1.5, 64.0, 0.5)
        player("n", 50.5, 64.0, 0.5, nether)
        take()
        a.simulatePlayerMove(Location(world, 0.9, 64.0, 0.5)) // within the block, unreported
        occ.moveTo(Area.sphere(world, 50.5, 64.0, 0.5, 3.0))
        assertEquals(listOf("exit O a AREA_MOVED 1", "exit O b AREA_MOVED 0", "last O b AREA_MOVED 0"), take())
        occ.moveTo(Area.sphere(world, 1.9, 64.0, 0.5, 0.5))
        assertEquals(listOf("first O b AREA_MOVED 1", "enter O b AREA_MOVED 1"), take(), "a at 0.9 is 1.0 away; b at 1.5 is inside")
        b.simulatePlayerMove(Location(world, 1.1, 64.0, 0.5)) // out of the small ball, within the block
        occ.moveTo(Area.sphere(nether, 50.5, 64.0, 0.5, 1.0))
        assertEquals(listOf("enter O n AREA_MOVED 2", "exit O b AREA_MOVED 1"), take(), "across worlds")
        assertEquals(listOf("n"), occ.players.map { it.name })
    }

    @Test
    fun aRiderWhoCrossedABlockSinceThePollIsCountedByTheNextPoll() {
        // Eighth review, finding 1 (accepted and documented): a moveTo tests a rider at their last polled block; the
        // next poll, a tick later, reports the new block as a step and enters them.
        val occ = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("O"))
        val p = player("p", 0.5, 64.0, 0.5)
        world.spawn(Location(world, 0.5, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick() // mounted and polled at block 0
        p.location = Location(world, 5.5, 64.0, 0.5) // carried five blocks on: no event, not polled yet
        occ.moveTo(Area.sphere(world, 5.5, 64.0, 0.5, 1.0))
        assertEquals(emptyList<String>(), take(), "tested at the last polled block")
        assertFalse(occ.contains(p))
        server.scheduler.performOneTick()
        assertEquals(listOf("first O p VEHICLE 1", "enter O p VEHICLE 1"), take(), "the next poll counts them")
        p.location = Location(world, 9.5, 64.0, 0.5)
        occ.moveTo(occ.area)
        assertTrue(occ.contains(p), "and a rider who left since the poll is still counted until it")
        server.scheduler.performOneTick()
        assertEquals(listOf("exit O p VEHICLE 0", "last O p VEHICLE 0"), take())
    }

    @Test
    fun theShapedOccupancyCountFollowsRegistrationsAndReTestsWorkAgainAfterAClose() {
        // Tenth review, finding 2: with none registered, the occupancy-only work on teleports, game mode and riding
        // changes is skipped; the count that decides it must follow every way a shape comes and goes.
        val service = triggers as TriggerService
        fun count() = service.index.shapedOccupancies
        assertEquals(0, count())
        val boxed = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("box"))
        triggers.area(owner, Area.sphere(world, 0.5, 64.0, 0.5, 2.0), object : AreaHandler {})
        assertEquals(0, count(), "box occupancies and shaped areas don't count")
        val first = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("A"))
        assertEquals(1, count())
        boxed.moveTo(Area.sphere(world, 20.5, 64.0, 0.5, 2.0))
        assertEquals(2, count(), "a box moved onto a shape")
        boxed.translate(1, 0, 0)
        assertEquals(2, count())
        boxed.moveTo(box(0, 0, 5, 5))
        assertEquals(1, count(), "and back onto a box")
        first.close()
        first.close()
        assertEquals(0, count())

        // Nothing shaped: a same-block teleport and a mode change do no occupancy work (and nothing breaks).
        val p = player("p", 0.1, 64.0, 0.5)
        p.teleport(Location(world, 0.8, 64.0, 0.5))
        p.gameMode = GameMode.CREATIVE
        p.teleport(Location(world, 0.1, 64.0, 0.5))
        take()

        // Registered again: the re-tests are back.
        val again = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("B"))
        assertEquals(1, count())
        assertEquals(listOf("first B p REGISTER 1", "enter B p REGISTER 1"), take().filter { " B " in it })
        p.teleport(Location(world, 0.8, 64.0, 0.5)) // within the block
        assertEquals(listOf("exit B p TELEPORT 0", "last B p TELEPORT 0"), take().filter { " B " in it })
        p.simulatePlayerMove(Location(world, 0.1, 64.0, 0.5))
        p.gameMode = GameMode.ADVENTURE
        assertEquals(listOf("first B p MOVE 1", "enter B p MOVE 1"), take().filter { " B " in it })
        assertTrue(again.contains(p))

        val other = MockBukkit.createMockPlugin("Other")
        triggers.occupancy(other, Area.cylinder(world, 40.5, 64.0, 0.5, 2.0, 3.0), recorder("C"))
        assertEquals(2, count())
        server.pluginManager.disablePlugin(other)
        assertEquals(1, count(), "an owner disabling takes its shapes with it")
        val gone = server.addSimpleWorld("instance")
        triggers.occupancy(owner, Area.sphere(gone, 0.5, 64.0, 0.5, 2.0), recorder("I"))
        assertEquals(2, count())
        assertTrue(server.unloadWorld(gone, false))
        assertEquals(1, count(), "a world unloading too")
        server.pluginManager.disablePlugin(server.pluginManager.getPlugin("SneakyUtils")!!)
        assertEquals(0, count(), "and the shutdown")
    }

    @Test
    fun theResyncAfterARidersNestedTeleportKeepsTheVehicleCause() {
        // Twelfth review: a rider is teleported within the block; a handler teleports them back during that event; the
        // outer destination wins, and the next tick's correction must report what it finds as VEHICLE too.
        var nested = false
        lateinit var rider: PlayerMock
        triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), object : OccupancyHandler {
            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter $cause"
            }

            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "exit $cause"
                if (nested) return
                nested = true
                val back = Location(world, 0.1, 64.0, 0.5)
                server.pluginManager.callEvent(org.bukkit.event.player.PlayerTeleportEvent(rider, rider.location, back,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN))
            }
        })
        rider = player("p", 0.1, 64.0, 0.5)
        world.spawn(Location(world, 0.1, 64.0, 0.5), ArmorStand::class.java).addPassenger(rider)
        server.scheduler.performOneTick()
        take()
        val to = Location(world, 0.8, 64.0, 0.5)
        server.pluginManager.callEvent(org.bukkit.event.player.PlayerTeleportEvent(rider, rider.location, to,
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN))
        rider.location = to // the outer teleport is applied last
        assertEquals(listOf("exit VEHICLE", "enter VEHICLE"), take(), "out with the outer teleport, back in with the nested one")
        server.scheduler.performOneTick()
        assertEquals(listOf("exit VEHICLE"), take(), "the correction finds them outside: still a rider's move")
    }

    @Test
    fun theResyncAfterANestedTeleportDuringADismountKeepsTheMoveCause() {
        // Thirteenth review: the dismount teleport (seat to ground, cause MOVE) takes the player out of the ball; the
        // exit handler teleports them back; the dismount destination wins, and the correction's exit is MOVE too.
        var nested = false
        lateinit var p: PlayerMock
        triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), object : OccupancyHandler {
            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter $cause"
            }

            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "exit $cause"
                if (nested) return
                nested = true
                server.pluginManager.callEvent(org.bukkit.event.player.PlayerTeleportEvent(p, p.location,
                    Location(world, 0.1, 64.0, 0.5), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN))
            }
        })
        p = player("p", 0.1, 64.0, 0.5)
        world.spawn(Location(world, 0.1, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick()
        p.leaveVehicle()
        server.scheduler.performOneTick() // detached; still inside the ball
        take()
        val ground = Location(world, 0.8, 64.0, 0.5)
        server.pluginManager.callEvent(org.bukkit.event.player.PlayerTeleportEvent(p, p.location, ground,
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.DISMOUNT))
        p.location = ground // the dismount teleport is applied last
        assertEquals(listOf("exit MOVE", "enter TELEPORT"), take())
        server.scheduler.performOneTick()
        assertEquals(listOf("exit MOVE"), take(), "the correction carries the dismount's classification")
    }

    @Test
    fun aGameModeChangeNestedInAnotherIsCorrectedOnTheNextTick() {
        // Fifteenth review: turning spectator takes the player out; their exit handler switches them to creative while
        // the spectator change is still being applied, so the engine believes creative, but spectator wins.
        var nested = false
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter $cause"
            }

            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "exit $cause"
                if (nested) return
                nested = true
                player.gameMode = GameMode.CREATIVE
            }
        })
        val gate = ArrayList<String>()
        triggers.area(owner, box(10, 0, 15, 5), AreaHandler.onEnter { _, _, _ -> gate += "enter" })
        val p = player("p", 2.5, 64.0, 2.5)
        take()
        p.gameMode = GameMode.SPECTATOR
        assertEquals(GameMode.SPECTATOR, p.gameMode, "the outer change won")
        assertEquals(listOf("exit GAME_MODE", "enter GAME_MODE"), take(), "the nested change put them back, wrongly")
        server.scheduler.performOneTick()
        assertEquals(listOf("exit GAME_MODE"), take(), "corrected against the real mode")
        assertEquals(0, occ.count)
        assertEquals(0, (triggers as TriggerService).pendingGameModeCount)
        p.simulatePlayerMove(Location(world, 12.5, 64.0, 2.5))
        assertEquals(emptyList<String>(), gate, "and the area's spectator gate holds again")
    }

    @Test
    fun aCancelledGameModeChangeLeavesTheCountAlone() {
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("O"))
        val p = player("p", 2.5, 64.0, 2.5)
        take()
        var priority = "LOWEST"
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
            fun early(e: org.bukkit.event.player.PlayerGameModeChangeEvent) {
                if (priority == "LOWEST") e.isCancelled = true
            }

            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            fun late(e: org.bukkit.event.player.PlayerGameModeChangeEvent) {
                if (priority == "MONITOR") e.isCancelled = true
            }
        }, owner)
        p.gameMode = GameMode.SPECTATOR
        server.scheduler.performOneTick()
        assertEquals(GameMode.SURVIVAL, p.gameMode)
        assertEquals(emptyList<String>(), take(), "cancelled before SneakyUtils saw it: nothing happened")
        assertEquals(1, occ.count)

        // Cancelled by a listener after SneakyUtils' own (bad manners, but possible): undone on the next tick.
        priority = "MONITOR"
        p.gameMode = GameMode.SPECTATOR
        assertEquals(GameMode.SURVIVAL, p.gameMode)
        assertEquals(listOf("exit O p GAME_MODE 0", "last O p GAME_MODE 0"), take())
        server.scheduler.performOneTick()
        assertEquals(listOf("first O p GAME_MODE 1", "enter O p GAME_MODE 1"), take())
        assertEquals(1, occ.count)
    }

    @Test
    fun aRiderCarriedByATeleportWithinTheBlockIsReTested() {
        // Fifth review, finding 3: a rider's same-world teleport counts as vehicle movement for areas; it still
        // re-tests shaped occupancies, with cause VEHICLE.
        val occ = triggers.occupancy(owner, Area.sphere(world, 0.1, 64.0, 0.5, 0.2), recorder("O"))
        val p = player("p", 0.1, 64.0, 0.5)
        world.spawn(Location(world, 0.1, 64.0, 0.5), ArmorStand::class.java).addPassenger(p)
        server.scheduler.performOneTick()
        take()
        val to = Location(world, 0.8, 64.0, 0.5)
        server.pluginManager.callEvent(org.bukkit.event.player.PlayerTeleportEvent(p, p.location, to,
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN))
        p.location = to
        assertEquals(listOf("exit O p VEHICLE 0", "last O p VEHICLE 0"), take())
        assertFalse(occ.contains(p))
    }

    @Test
    fun aMoveFromARespawnHandlerKeepsTheRespawnDestination() {
        // While a respawn (or teleport, or move) is handled, Bukkit still reports the old position (here the death
        // spot, in the same block): the engine's destination is the one to test.
        world.spawnLocation = Location(world, 10.0, 64.0, 0.0)
        val spawn = world.spawnLocation // MockBukkit keeps it block-aligned
        val occ = triggers.occupancy(owner, box(100, 0, 105, 5), recorder("O"))
        var bukkitX = 0.0
        triggers.area(owner, box(10, 0, 10, 0), AreaHandler.onEnter { pl, _, cause ->
            bukkitX = pl.location.x
            if (cause == AreaCause.TELEPORT) occ.moveTo(Area.sphere(spawn, 0.2))
        })
        val p = player("p", 10.6, 64.0, 0.4)
        occ.moveTo(box(100, 0, 105, 5))
        p.health = 0.0
        p.respawn()
        assertEquals(10.6, bukkitX, 1e-9, "Bukkit still had the death spot during the respawn")
        assertTrue(occ.contains(p), "tested at the respawn point")
    }

    @Test
    fun anOwnerDisabledByOneCallbackGetsNoMoreOfTheSameDelivery() {
        // Review finding 4.
        val other = MockBukkit.createMockPlugin("Other")
        triggers.occupancy(other, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "first"
                server.pluginManager.disablePlugin(other)
            }

            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter"
            }
        })
        player("a", 2.5, 64.0, 2.5)
        assertEquals(listOf("first"), take())

        val third = MockBukkit.createMockPlugin("Third")
        val out = triggers.occupancy(third, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "exit"
                server.pluginManager.disablePlugin(third)
            }

            override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "last"
            }
        })
        take()
        (server.getPlayer("a") as PlayerMock).simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        assertEquals(listOf("exit"), take())
        assertFalse(out.active)

        // An explicit close from onFirstIn keeps the order: the enter still comes, then the exit and last-out.
        triggers.occupancy(owner, box(0, 0, 5, 5), object : OccupancyHandler {
            override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "first"
                occupancy.close()
            }

            override fun onEnter(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "enter"
            }

            override fun onExit(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "exit $cause"
            }

            override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) {
                log += "last $cause"
            }
        })
        (server.getPlayer("a") as PlayerMock).simulatePlayerMove(Location(world, 2.5, 64.0, 2.5))
        assertEquals(listOf("first", "enter", "exit UNREGISTER", "last UNREGISTER"), take())
    }

    @Test
    fun turningSpectatorWhileDeadLeaves() {
        // Review finding 3.
        val occ = triggers.occupancy(owner, box(0, 0, 5, 5), recorder("O"))
        val p = player("p", 2.5, 64.0, 2.5)
        take()
        p.health = 0.0
        p.gameMode = GameMode.SPECTATOR
        assertEquals(listOf("exit O p GAME_MODE 0", "last O p GAME_MODE 0"), take())
        assertEquals(0, occ.count)
    }

    @Test
    fun labelsStatsAndTheCommand() {
        val occ = triggers.labelled("owner:station").occupancy(owner, box(0, 0, 5, 5), OccupancyHandler.of(null, null))
        val own = triggers.occupancy(owner, box(0, 0, 5, 5), OccupancyHandler.of(null, null),
            OccupancyOptions.builder().label("owner:other").build())
        assertEquals("owner:station", occ.label)
        assertEquals("owner:other", triggers.labelled("owner:station").occupancy(owner, box(0, 0, 5, 5),
            OccupancyHandler.of(null, null), OccupancyOptions.builder().label("owner:other").build()).label, "options win")
        assertEquals("owner:other", own.label)
        val stats = triggers.stats()
        assertEquals(3, stats.count("occupancy"))
        assertTrue(stats.subscriptions.any { it.kind == "occupancy" && it.label == "owner:station" && it.id == occ.id })
        assertTrue(occ.id.startsWith("Owner:occupancy#"))
        player("p", 2.5, 64.0, 2.5)
        val lines = TriggersCommand { SneakyUtils.getTriggers() as TriggerService }.statsLines()
        assertTrue(lines.any { it.contains("occupancies 3") }, lines.toString())
        assertTrue(lines.any { it.contains("Owner: occupancy 3") }, lines.toString())
    }

    @Test
    fun javaStyleListenersAndNoTickerWhileNothingMoves() {
        val firsts = ArrayList<String>()
        val lasts = ArrayList<String>()
        triggers.occupancy(owner, box(0, 0, 5, 5), OccupancyHandler.of(
            { _, p, cause -> firsts += "${p.name} $cause" },
            { _, p, cause -> lasts += "${p.name} $cause" },
        ))
        val su = server.pluginManager.getPlugin("SneakyUtils")!!
        assertEquals(0, server.scheduler.pendingTasks.count { it.owner === su }, "an occupancy schedules nothing")
        val p = player("p", 2.5, 64.0, 2.5)
        p.simulatePlayerMove(Location(world, 20.5, 64.0, 2.5))
        assertEquals(listOf("p TELEPORT"), firsts)
        assertEquals(listOf("p MOVE"), lasts)
    }
}
