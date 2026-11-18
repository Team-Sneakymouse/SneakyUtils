package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.OccupancyOptions
import com.sneakyutils.triggers.RiderPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Occupancy registrations in the engine core: who stays through death and game mode changes, and re-anchoring with
 * [MovementCore.moveArea] (membership and the index). The service's counting and handlers: OccupancyBukkitTest.
 */
class OccupancyEngineTest {
    private val f = EngineFixture()
    private fun take() = f.sink.take()
    private fun box(x1: Int, x2: Int, w: java.util.UUID = f.world) = Area.box(w, x1, 60, 0, x2, 70, 10)

    @Test
    fun deadMembersStayUntilTheyRespawnElsewhereQuitOrChangeWorld() {
        f.occupancy("O", box(0, 10))
        f.area("A", 0, 60, 0, 10, 70, 10)
        val p = f.join("p", 5.5, 64.0, 5.5)
        assertEquals(listOf("in O p JOIN", "enter A p JOIN"), take())

        f.core.death(p.uuid)
        assertEquals(listOf("exit A p DEATH"), take(), "the area lets the dead go, the occupancy keeps them")
        f.move(p, 7.5, 64.0, 7.5)
        assertEquals(emptyList<String>(), take(), "nothing is evaluated while dead")
        f.core.respawn(p.uuid, f.world, 6.5, 64.0, 6.5)
        assertEquals(listOf("enter A p TELEPORT"), take(), "respawning inside: still a member, no calls")

        f.core.death(p.uuid)
        f.core.respawn(p.uuid, f.world, 50.5, 64.0, 5.5)
        assertEquals(listOf("exit A p DEATH", "out O p TELEPORT"), take(), "respawning elsewhere leaves")

        val q = f.join("q", 5.5, 64.0, 5.5)
        take()
        f.core.death(q.uuid)
        f.core.quit(q.uuid)
        assertEquals(listOf("exit A q DEATH", "out O q QUIT"), take())

        f.move(p, 5.5, 64.0, 5.5)
        take()
        f.core.death(p.uuid)
        f.core.respawn(p.uuid, f.nether, 5.5, 64.0, 5.5)
        assertEquals(listOf("exit A p DEATH", "out O p WORLD_CHANGE"), take())
    }

    @Test
    fun withoutCountDeadMembersLeaveOnDeath() {
        f.occupancy("D", box(0, 10), OccupancyOptions.builder().countDead(false).build())
        val p = f.join("p", 5.5, 64.0, 5.5)
        f.core.death(p.uuid)
        f.core.respawn(p.uuid, f.world, 5.5, 64.0, 5.5)
        assertEquals(listOf("in D p JOIN", "out D p DEATH", "in D p TELEPORT"), take())
    }

    @Test
    fun playersDeadWhenTheyJoinOrWhenItRegistersEnterOnlyOnRespawn() {
        val p = f.core.join(java.util.UUID.randomUUID().also { f.sink.names[it] = "p" }, null, f.world, 5.5, 64.0, 5.5,
            false, false, dead = true)
        f.occupancy("O", box(0, 10))
        assertEquals(emptyList<String>(), take())
        f.core.respawn(p.uuid, f.world, 5.5, 64.0, 5.5)
        assertEquals(listOf("in O p TELEPORT"), take())
    }

    @Test
    fun spectatorsLeaveAndComeBackUnlessIncluded() {
        f.occupancy("O", box(0, 10))
        f.occupancy("S", box(0, 10), OccupancyOptions.builder().includeSpectators(true).build())
        f.area("A", 0, 60, 0, 10, 70, 10)
        val p = f.join("p", 5.5, 64.0, 5.5)
        assertEquals(listOf("in O p JOIN", "in S p JOIN", "enter A p JOIN"), take())

        f.core.setSpectator(p, true)
        assertEquals(listOf("out O p GAME_MODE"), take(), "areas keep a member who turns spectator; occupancies don't")
        f.move(p, 7.5, 64.0, 7.5)
        assertEquals(emptyList<String>(), take(), "a spectator walking inside doesn't come back")
        f.core.setSpectator(p, false)
        assertEquals(listOf("in O p GAME_MODE"), take())

        f.core.setSpectator(p, true)
        f.move(p, 50.5, 64.0, 5.5)
        assertEquals(listOf("out O p GAME_MODE", "out S p MOVE", "exit A p MOVE"), take())
        f.move(p, 5.5, 64.0, 5.5)
        assertEquals(listOf("in S p MOVE"), take(), "only the occupancy that includes spectators takes one in")
    }

    @Test
    fun aDeadMemberWhoTurnsSpectatorLeavesOccupanciesThatExcludeSpectators() {
        f.occupancy("O", box(0, 10))
        f.occupancy("S", box(0, 10), OccupancyOptions.builder().includeSpectators(true).build())
        val p = f.join("p", 5.5, 64.0, 5.5)
        take()
        f.core.death(p.uuid)
        f.core.setSpectator(p, true)
        assertEquals(listOf("out O p GAME_MODE"), take(), "S includes spectators and keeps the dead member")
        f.core.setSpectator(p, false)
        assertEquals(emptyList<String>(), take(), "the dead enter nothing, whatever their game mode")
        f.core.respawn(p.uuid, f.world, 5.5, 64.0, 5.5)
        assertEquals(listOf("in O p TELEPORT"), take())

        f.core.death(p.uuid)
        f.core.setSpectator(p, true)
        take()
        f.core.respawn(p.uuid, f.world, 5.5, 64.0, 5.5)
        assertEquals(emptyList<String>(), take(), "respawning as a spectator: still out of O, still in S")
    }

    @Test
    fun ridersCountByDefaultAndTheRiderPolicyStillApplies() {
        f.occupancy("O", box(0, 10))
        f.occupancy("K", box(0, 10), OccupancyOptions.builder().riders(RiderPolicy.SKIP).build())
        val p = f.join("p", 50.5, 64.0, 5.5, mounted = true)
        f.move(p, 5.5, 64.0, 5.5)
        assertEquals(listOf("in O p VEHICLE"), take())
        f.core.setMounted(p, false)
        assertEquals(listOf("in K p MOVE"), take(), "dismounting inside a SKIP occupancy enters it on foot")
    }

    @Test
    fun moveKeepsTheOverlapAndQueuesEntersBeforeExits() {
        val o = f.occupancy("O", box(0, 10))
        f.join("a", 2.5, 64.0, 5.5)   // only in the old area
        val b = f.join("b", 8.5, 64.0, 5.5)   // in both
        f.join("c", 13.5, 64.0, 5.5)  // only in the new area
        f.join("far", 200.5, 64.0, 5.5)
        assertEquals(listOf("in O a JOIN", "in O b JOIN"), take())
        val version = f.index.version

        f.core.moveArea(o, box(5, 15))
        assertEquals(listOf("in O c AREA_MOVED", "out O a AREA_MOVED"), take(), "b stays without calls")
        assertEquals(version, f.index.version, "same chunks: no listing changed, so cached chunk arrays stay valid")
        assertTrue(b.isMember(o))
        assertEquals(2, o.members)

        f.core.moveArea(o, box(20, 30))
        assertEquals(listOf("out O b AREA_MOVED", "out O c AREA_MOVED"), take())
        assertTrue(f.index.version > version)
        val w = f.index.world(f.world)!!
        assertNull(w.areasAtBlock(0, 0), "left chunk 0")
        assertEquals(listOf(o), w.areasAtBlock(20, 0)!!.toList())
        assertEquals(1, f.index.chunkEntries)

        f.move(b, 25.5, 64.0, 5.5)
        assertEquals(listOf("in O b MOVE"), take(), "a player stepping in afterwards reads the new listing")
    }

    @Test
    fun aDeduplicatedSameBlockReportKeepsItsExactPositionAndASameAreaMoveReEvaluates() {
        val o = f.occupancy("O", Area.sphere(f.world, 0.8, 64.0, 0.5, 0.2))
        val p = f.join("p", 0.1, 64.0, 0.5)
        f.move(p, 0.8, 64.0, 0.5)
        assertEquals(emptyList<String>(), take(), "same block, not a teleport: no step, nothing evaluated")
        assertEquals(0.1, p.px, "the step position stays (pass-through paths start there)")
        assertEquals(0.8, p.lx, "the exact report is kept")
        val version = f.index.version
        f.core.moveArea(o, o.area)
        assertEquals(listOf("in O p AREA_MOVED"), take())
        assertEquals(version, f.index.version, "nothing reindexed")
        assertTrue(f.core.refinePosition(p, f.world, 0.85, 64.0, 0.5), "a refresh sets the exact position only")
        assertEquals(0.1, p.px)
        assertFalse(f.core.refinePosition(p, f.world, 1.2, 64.0, 0.5), "another block is left for its own report")
    }

    /**
     * Third review: areas, pass-through paths and watches must behave exactly as without occupancies. The same script
     * runs on two engines; one also registers, refreshes and moves a shaped occupancy around the player. Everything
     * delivered for the ordinary subscriptions must be identical.
     */
    @Test
    fun ordinaryAreasPassThroughAndWatchesAreUnaffectedByOccupancyRefreshes() {
        fun run(withOccupancy: Boolean): List<String> {
            val g = EngineFixture()
            // A custom disc around (0.1, 0.1) inside block 0,64,0; a pass-through gate in block 1,64,0.
            g.add("disc", Area.custom(g.world, 0, 64, 0, 0, 64, 0) { x, _, z -> (x - 0.1) * (x - 0.1) + (z - 0.1) * (z - 0.1) <= 0.09 })
            g.area("gate", 1, 64, 0, 1, 64, 0, com.sneakyutils.triggers.AreaOptions.builder().passThrough(true).build())
            val p = g.join("p", 0.1, 64.0, 0.1)
            g.stepWatch("steps", p)
            var o: AreaReg? = null
            fun occupancy(step: Int) {
                if (!withOccupancy) return
                // The player walked to (0.1, 0.9) within the block: only an occupancy refresh knows.
                assertTrue(g.core.refinePosition(p, g.world, 0.1, 64.0, 0.9))
                when (step) {
                    0 -> o = g.occupancy("O", Area.sphere(g.world, 0.1, 64.0, 0.9, 0.2))
                    1 -> g.core.moveArea(o!!, Area.sphere(g.world, 0.1, 64.0, 0.9, 0.3))
                    else -> g.core.moveArea(o!!, o!!.area)
                }
            }
            occupancy(0)
            g.core.setMounted(p, true)
            occupancy(1)
            g.core.setMounted(p, false)
            g.core.setSpectator(p, true)
            occupancy(2)
            g.core.setSpectator(p, false)
            occupancy(2)
            // From the last reported step (0.1, 0.1) this crosses the gate's block; from (0.1, 0.9) it would miss it.
            g.move(p, 2.5, 64.0, 1.5)
            if (withOccupancy) assertTrue(g.sink.log.any { it.startsWith("in O p") }, "the occupancy did see the player")
            return g.sink.take().filter { !it.startsWith("in O ") && !it.startsWith("out O ") }
        }
        val plain = run(false)
        assertEquals(plain, run(true))
        assertEquals(
            listOf("enter disc p JOIN", "step steps p 0,64,0->2,64,1", "exit disc p MOVE", "enter gate p MOVE", "exit gate p MOVE"),
            plain,
        )
    }

    @Test
    fun aMemberFoundOutsideOnAStateChangeLeavesWithTheRightCause() {
        // Third review, finding 3: each member walked out of the ball within their block (a refresh), then changed state.
        fun ball(name: String, options: OccupancyOptions = OccupancyOptions.DEFAULT) =
            f.occupancy(name, Area.sphere(f.world, 0.1, 64.0, 0.5, 0.2), options)
        ball("O")
        val p = f.join("p", 0.1, 64.0, 0.5)
        take()
        f.core.refinePosition(p, f.world, 0.8, 64.0, 0.5)
        f.core.setSpectator(p, true)
        assertEquals(listOf("out O p GAME_MODE"), take(), "a counted member who turns spectator leaves for that reason")
        f.core.setSpectator(p, false)
        assertEquals(emptyList<String>(), take(), "and is outside when they switch back")

        f.core.refinePosition(p, f.world, 0.1, 64.0, 0.5)
        f.core.moveArea(f.index.world(f.world)!!.areasAtBlock(0, 0)!![0], f.index.world(f.world)!!.areasAtBlock(0, 0)!![0].area)
        assertEquals(listOf("in O p AREA_MOVED"), take())
        f.core.refinePosition(p, f.world, 0.8, 64.0, 0.5)
        f.core.setMounted(p, true)
        assertEquals(listOf("out O p MOVE"), take(), "they walked out before mounting")

        f.core.refinePosition(p, f.world, 0.1, 64.0, 0.5)
        f.core.moveArea(f.index.world(f.world)!!.areasAtBlock(0, 0)!![0], f.index.world(f.world)!!.areasAtBlock(0, 0)!![0].area)
        take()
        f.core.refinePosition(p, f.world, 0.8, 64.0, 0.5)
        f.core.setMounted(p, false)
        assertEquals(listOf("out O p VEHICLE"), take(), "they rode out before dismounting")

        ball("D", OccupancyOptions.builder().countDead(false).build())
        val q = f.join("q", 0.1, 64.0, 0.5)
        take()
        f.core.refinePosition(q, f.world, 0.8, 64.0, 0.5)
        f.core.death(q.uuid)
        assertEquals(listOf("out D q DEATH"), take().filter { " D " in it }, "a counted member who dies leaves with DEATH")
    }

    @Test
    fun aTeleportWithinTheBlockReEvaluatesShapedOccupanciesOnly() {
        f.occupancy("near", Area.sphere(f.world, 0.1, 64.0, 0.5, 0.2))
        f.occupancy("far", Area.sphere(f.world, 0.8, 64.0, 0.5, 0.2))
        f.occupancy("box", Area.box(f.world, 0, 64, 0, 0, 64, 0))
        val wide = f.occupancy("wide", Area.sphere(f.world, 0.5, 64.0, 0.5, 0.6)) // holds both spots: p stays a member
        f.add("disc", Area.custom(f.world, 0, 64, 0, 0, 64, 0) { x, _, _ -> x < 0.5 })
        val p = f.join("p", 0.1, 64.0, 0.5)
        f.stepWatch("steps", p, com.sneakyutils.triggers.WatchOptions.builder().includeTeleports(true).build())
        assertEquals(listOf("in near p JOIN", "in box p JOIN", "in wide p JOIN", "enter disc p JOIN"), take())
        val steps = f.core.steps
        f.teleport(p, 0.8, 64.0, 0.5)
        assertEquals(listOf("out near p TELEPORT", "in far p TELEPORT"), take(),
            "the ordinary disc (tested at the last step), the box and the watch hear nothing, as on main")
        assertEquals(steps, f.core.steps, "still no step")
        assertEquals(1, wide.members, "a member who stays inside a shape is not entered twice")
        assertEquals(0.1, p.px)
        f.move(p, 0.1, 64.0, 0.5)
        assertEquals(emptyList<String>(), take(), "walking (or riding) within the block stays unevaluated")
        f.core.position(p, f.world, 0.1, 64.0, 0.5, teleport = true)
        assertEquals(listOf("out far p TELEPORT", "in near p TELEPORT"), take(),
            "a teleport to the spot already stored is tested all the same")
        f.core.setMounted(p, true)
        f.core.position(p, f.world, 0.8, 64.0, 0.5, teleport = false, shapeRetest = com.sneakyutils.triggers.AreaCause.VEHICLE)
        assertEquals(listOf("out near p VEHICLE", "in far p VEHICLE"), take(),
            "a rider carried by a teleport: vehicle movement for areas, re-tested for shaped occupancies")
        assertEquals(steps, f.core.steps)
        f.core.setMounted(p, false)
        f.core.position(p, f.world, 0.1, 64.0, 0.5, teleport = true)
        take()
        f.core.death(p.uuid)
        f.teleport(p, 0.15, 64.0, 0.5)
        assertEquals(listOf("exit disc p DEATH"), take(), "nothing is evaluated for the dead")
    }

    @Test
    fun aStateChangeIsTheCauseOnlyWhereItDecidesWhoCounts() {
        // Seventh review, finding 1: each player walked into the ball within their block (a refresh), then changed state.
        fun ball(name: String, options: OccupancyOptions = OccupancyOptions.DEFAULT) =
            f.occupancy(name, Area.sphere(f.world, 0.8, 64.0, 0.5, 0.2), options)
        ball("excl")
        ball("incl", OccupancyOptions.builder().includeSpectators(true).build())
        val p = f.join("p", 0.1, 64.0, 0.5)
        f.core.setSpectator(p, true)
        f.core.refinePosition(p, f.world, 0.8, 64.0, 0.5)
        f.core.setSpectator(p, false)
        assertEquals(listOf("in excl p GAME_MODE", "in incl p MOVE"), take(),
            "leaving spectator is why excl counts them; incl would have anyway: they walked in")
        f.core.quit(p.uuid)
        take()
        val q = f.join("q", 0.1, 64.0, 0.5)
        f.core.refinePosition(q, f.world, 0.8, 64.0, 0.5)
        f.core.setSpectator(q, true)
        assertEquals(listOf("in incl q MOVE"), take(), "turning spectator changed nothing for incl")
        f.core.quit(q.uuid)
        take()

        val g = EngineFixture()
        fun riders(name: String, policy: RiderPolicy) =
            g.occupancy(name, Area.sphere(g.world, 0.8, 64.0, 0.5, 0.2), OccupancyOptions.builder().riders(policy).build())
        riders("allow", RiderPolicy.ALLOW)
        riders("only", RiderPolicy.ONLY)
        riders("skip", RiderPolicy.SKIP)
        val r = g.join("r", 0.1, 64.0, 0.5)
        g.core.refinePosition(r, g.world, 0.8, 64.0, 0.5)
        g.core.setMounted(r, true)
        assertEquals(listOf("in allow r MOVE", "in only r VEHICLE"), g.sink.take(),
            "they walked into allow; mounting is why only counts them")
        g.core.refinePosition(r, g.world, 0.1, 64.0, 0.5)
        g.core.setMounted(r, false)
        assertEquals(listOf("out allow r VEHICLE", "out only r VEHICLE"), g.sink.take(), "they rode out")
        g.core.setMounted(r, true)
        g.core.refinePosition(r, g.world, 0.8, 64.0, 0.5)
        g.core.setMounted(r, false)
        assertEquals(listOf("in allow r VEHICLE", "in skip r MOVE"), g.sink.take(),
            "they rode into allow; dismounting is why skip counts them")
    }

    @Test
    fun moveAcrossWorldsLeavesTheOldWorldsIndex() {
        val o = f.occupancy("O", box(0, 10))
        val p = f.join("p", 5.5, 64.0, 5.5)
        f.join("n", 5.5, 64.0, 5.5, w = f.nether)
        take()
        f.core.moveArea(o, box(0, 10, f.nether))
        assertEquals(listOf("in O n AREA_MOVED", "out O p AREA_MOVED"), take())
        assertNull(f.index.world(f.world), "the old world's index went with its last area")
        assertEquals(1, f.index.worldCount)
        assertEquals(1, f.index.areaCount)
        f.move(p, 6.5, 64.0, 6.5)
        assertEquals(emptyList<String>(), take())
        f.core.moveArea(o, box(0, 10))
        assertEquals(listOf("in O p AREA_MOVED", "out O n AREA_MOVED"), take())
        assertEquals(1, f.index.chunkEntries)
    }

    @Test
    fun aMovedAreaKeepsItsRegistrationOrderInEveryChunk() {
        val a = f.area("A", 0, 60, 0, 5, 70, 5)
        val o = f.occupancy("O", Area.box(f.world, 20, 60, 0, 25, 70, 5))
        val b = f.area("B", 0, 60, 0, 5, 70, 5)
        f.core.moveArea(o, Area.box(f.world, 0, 60, 0, 5, 70, 5))
        assertEquals(listOf(a, o, b), f.index.world(f.world)!!.areasAtBlock(0, 0)!!.toList())
        val p = f.join("p", 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p JOIN", "in O p JOIN", "enter B p JOIN"), take(), "enters in registration order")
        assertEquals(3, p.memberCount)
    }

    @Test
    fun deadPlayersDontEnterOnAMoveButADeadMemberStaysWhileCovered() {
        val o = f.occupancy("O", box(0, 10))
        val m = f.join("m", 5.5, 64.0, 5.5)
        val d = f.join("d", 14.5, 64.0, 5.5)
        take()
        f.core.death(m.uuid)
        f.core.death(d.uuid)
        f.core.moveArea(o, box(4, 15))
        assertEquals(emptyList<String>(), take(), "m stays (still covered), d doesn't enter while dead")
        f.core.moveArea(o, box(20, 30))
        assertEquals(listOf("out O m AREA_MOVED"), take())
    }

    @Test
    fun onlyOpenOccupanciesMove() {
        val o = f.occupancy("O", box(0, 10))
        val a = f.area("A", 0, 60, 0, 10, 70, 10)
        assertThrows(IllegalArgumentException::class.java) { f.core.moveArea(a, box(5, 15)) }
        f.core.removeArea(o, false)
        assertThrows(IllegalStateException::class.java) { f.core.moveArea(o, box(5, 15)) }
        assertFalse(o.active)
    }

    @Test
    fun aMoveFromInsideAPredicateIsRefused() {
        lateinit var o: AreaReg
        var refused = 0
        f.area("P", 0, 60, 0, 10, 70, 10)
        o = f.occupancy("O", box(100, 110))
        f.add("Q", Area.custom(f.world, 0, 60, 0, 10, 70, 10) { _, _, _ ->
            try {
                f.core.moveArea(o, box(0, 10))
            } catch (e: IllegalStateException) {
                refused++
            }
            true
        })
        f.join("p", 5.5, 64.0, 5.5)
        assertTrue(refused > 0)
        assertEquals(100, o.area.minX, "the area didn't move")
        assertEquals(listOf("enter P p JOIN", "enter Q p JOIN"), take())
    }

    @Test
    fun movingEveryTickOverAStandingPlayerAndCarryingARider() {
        // A 5-block station flies along x one block a tick for 120 ticks: over a player standing at x 60, and with a
        // rider carried along. The rider (reported where the station now is, like the mounted poll) never leaves; the
        // standing player gets one enter and one exit; the index ends with exactly the station's current chunks.
        val o = f.occupancy("O", box(0, 4))
        val rider = f.join("rider", 2.5, 64.0, 5.5, mounted = true)
        f.join("stand", 60.5, 64.0, 5.5)
        assertEquals(listOf("in O rider JOIN"), take())
        var area = o.area
        for (tick in 1..120) {
            area = area.translate(1, 0, 0)
            f.core.moveArea(o, area)
            f.move(rider, 2.5 + tick, 64.0, 5.5)
        }
        assertEquals(listOf("in O stand AREA_MOVED", "out O stand AREA_MOVED"), take())
        assertTrue(rider.isMember(o))
        assertEquals(1, o.members)
        assertEquals((area.maxX shr 4) - (area.minX shr 4) + 1, f.index.chunkEntries)
    }
}
