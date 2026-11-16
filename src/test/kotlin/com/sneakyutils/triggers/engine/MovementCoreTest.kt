package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockOffset
import com.sneakyutils.triggers.RiderPolicy
import com.sneakyutils.triggers.WatchOptions
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The movement engine without a server: every rule of the spec's movement section. */
class MovementCoreTest {
    private val f = EngineFixture()
    private val core = f.core
    private val sink = f.sink

    private val pt = AreaOptions.builder().passThrough(true).build()
    private val allow = AreaOptions.builder().riders(RiderPolicy.ALLOW).build()
    private val only = AreaOptions.builder().riders(RiderPolicy.ONLY).build()

    // ---------------------------------------------------------------- basic enter / exit

    @Test
    fun enterAndExitOnFoot() {
        f.area("A", 10, 60, 10, 12, 70, 12)
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.move(p, 11.5, 64.0, 11.5)
        f.move(p, 11.5, 64.0, 12.5)
        f.move(p, 11.5, 64.0, 13.5)
        assertEquals(listOf("enter A p MOVE", "exit A p MOVE"), sink.take())
    }

    @Test
    fun sameBlockReportsAreDroppedBeforeAnyWork() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.stepWatch("w", p)
        sink.take()
        val steps = core.steps
        f.move(p, 0.9, 64.7, 0.1)
        f.move(p, 0.2, 64.0, 0.99)
        assertEquals(steps, core.steps)
        assertEquals(emptyList<String>(), sink.take())
    }

    @Test
    fun noOpStepsAreCountedAndDoNothing() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        repeat(100) { f.move(p, it + 1.5, 64.0, 0.5) }
        assertEquals(100L, core.steps)
        assertEquals(100L, core.noopSteps)
        assertEquals(emptyList<String>(), sink.take())
    }

    @Test
    fun adjacentAreasExitBeforeEnter() {
        f.area("A", 0, 60, 0, 4, 70, 4)
        f.area("B", 5, 60, 0, 9, 70, 4)
        val p = f.join("p", 4.5, 64.0, 2.5)
        assertEquals(listOf("enter A p JOIN"), sink.take())
        f.move(p, 5.5, 64.0, 2.5)
        assertEquals(listOf("exit A p MOVE", "enter B p MOVE"), sink.take())
    }

    @Test
    fun deliveryOrderIsEnteredOrderForExitsRegistrationOrderForEntersAndJoinOrderForPlayers() {
        // The documented order (docs/triggers.md, "Order"). It used to depend on swap-removal, reverse iteration and
        // HashMap order, so a handler's side effects (a kick, a close) could cut off different events.
        val steps = AreaOptions.builder().stepEvents(true).build()
        f.area("C", 0, 60, 0, 9, 70, 9, steps)
        f.area("A", 0, 60, 0, 3, 70, 9, steps)
        f.area("B", 0, 60, 0, 9, 70, 3, steps)
        val p = f.join("p", 20.5, 64.0, 20.5)
        f.move(p, 1.5, 64.0, 1.5)
        assertEquals(listOf("enter C p MOVE", "enter A p MOVE", "enter B p MOVE"), sink.take(), "registration order")
        f.move(p, 1.5, 64.0, 5.5) // leaves B only
        f.move(p, 1.5, 64.0, 1.5) // back into B, which is now the last one entered
        f.move(p, 2.5, 64.0, 1.5)
        assertEquals(listOf(
            "exit B p MOVE", "inside C p MOVE", "inside A p MOVE",
            "enter B p MOVE", "inside C p MOVE", "inside A p MOVE",
            "inside C p MOVE", "inside A p MOVE", "inside B p MOVE",
        ), sink.take(), "steps inside in the order entered")
        f.move(p, 20.5, 64.0, 20.5)
        assertEquals(listOf("exit C p MOVE", "exit A p MOVE", "exit B p MOVE"), sink.take(), "exits in the order entered")
        f.move(p, 1.5, 64.0, 1.5)
        f.move(p, 5.5, 64.0, 1.5) // leaves A, keeps C and B
        core.quit(p.uuid)
        assertEquals(listOf(
            "enter C p MOVE", "enter A p MOVE", "enter B p MOVE",
            "exit A p MOVE", "inside C p MOVE", "inside B p MOVE",
            "exit C p QUIT", "exit B p QUIT",
        ), sink.take())

        f.join("q", 5.5, 64.0, 5.5)
        f.join("r", 6.5, 64.0, 6.5)
        f.join("o", 7.5, 64.0, 7.5)
        sink.take()
        val all = f.area("all", 0, 60, 0, 9, 70, 9, AreaOptions.builder().fireOnRegister(true).build())
        core.removeArea(all, false)
        assertEquals(listOf(
            "enter all q REGISTER", "enter all r REGISTER", "enter all o REGISTER",
            "exit all q UNREGISTER", "exit all r UNREGISTER", "exit all o UNREGISTER",
        ), sink.take(), "players in join order")
    }

    @Test
    fun passThroughsAlongThePathFollowRegistrationOrder() {
        f.area("late", 40, 60, 0, 41, 70, 1, pt) // chunk 2, registered first
        f.area("early", 20, 60, 0, 21, 70, 1, pt) // chunk 1, registered second
        val p = f.join("p", 2.5, 64.0, 0.5)
        f.move(p, 60.5, 64.0, 0.5)
        assertEquals(listOf("enter late p MOVE", "exit late p MOVE", "enter early p MOVE", "exit early p MOVE"), sink.take())
    }

    @Test
    fun joinInsideFiresJoinOrCountsSilently() {
        f.area("loud", 0, 60, 0, 5, 70, 5)
        f.area("quiet", 0, 60, 0, 5, 70, 5, AreaOptions.builder().fireOnJoin(false).build())
        val p = f.join("p", 2.5, 64.0, 2.5)
        assertEquals(listOf("enter loud p JOIN"), sink.take())
        assertEquals(2, p.memberCount)
        f.move(p, 2.5, 64.0, 3.5)
        f.move(p, 20.5, 64.0, 2.5)
        assertEquals(listOf("exit loud p MOVE"), sink.take(), "a silent stay gets no exit either")
    }

    @Test
    fun teleportInAndOut() {
        f.area("A", 100, 60, 100, 105, 70, 105)
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.teleport(p, 102.5, 64.0, 102.5)
        f.teleport(p, 0.5, 64.0, 0.5)
        assertEquals(listOf("enter A p TELEPORT", "exit A p TELEPORT"), sink.take())
    }

    @Test
    fun aMoveLongerThanTheSegmentLimitCountsAsATeleport() {
        f.area("A", 100, 60, 0, 101, 70, 1)
        f.area("crossed", 50, 60, 0, 51, 70, 1, pt)
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.move(p, 100.5, 64.0, 0.5) // 100 blocks in one move: a lag spike
        assertEquals(listOf("enter A p TELEPORT"), sink.take(), "no path checks: the pass-through area is not crossed")
        assertEquals(1L, core.longMoves)
    }

    @Test
    fun worldChangeExitsOldAreasAndEntersNewOnes() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        f.area("N", 0, 60, 0, 5, 70, 5, w = f.nether)
        val p = f.join("p", 2.5, 64.0, 2.5)
        f.move(p, 2.5, 64.0, 2.5, f.nether)
        assertEquals(listOf("enter A p JOIN", "exit A p WORLD_CHANGE", "enter N p WORLD_CHANGE"), sink.take())
        assertSame(f.nether, p.worldId)
    }

    @Test
    fun quitInsideExitsAndForgetsThePlayer() {
        val a = f.area("A", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 2.5, 64.0, 2.5)
        core.quit(p.uuid)
        assertEquals(listOf("enter A p JOIN", "exit A p QUIT"), sink.take())
        assertNull(core.state(p.uuid))
        assertTrue(p.removed)
        assertEquals(0, a.members)
        f.move(p, 3.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "reports for a removed state are ignored")
    }

    @Test
    fun deathInsideExitsAndRespawnEntersEvenOnTheSameBlock() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 2.5, 64.0, 2.5)
        core.death(p.uuid)
        f.move(p, 3.5, 64.0, 2.5) // a dead player's position still updates, nothing fires
        core.respawn(p.uuid, f.world, 3.5, 64.0, 2.5)
        assertEquals(listOf("enter A p JOIN", "exit A p DEATH", "enter A p TELEPORT"), sink.take())
    }

    @Test
    fun respawnInAnotherWorld() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        f.area("N", 0, 60, 0, 5, 70, 5, w = f.nether)
        val p = f.join("p", 2.5, 64.0, 2.5)
        core.death(p.uuid)
        core.respawn(p.uuid, f.nether, 1.5, 64.0, 1.5)
        assertEquals(listOf("enter A p JOIN", "exit A p DEATH", "enter N p WORLD_CHANGE"), sink.take())
    }

    // ---------------------------------------------------------------- riders and spectators

    @Test
    fun skipAreaIgnoresRidersAndEntersOnDismountInside() {
        f.area("road", 10, 60, 0, 20, 70, 5)
        val p = f.join("p", 0.5, 64.0, 2.5, mounted = true)
        f.move(p, 15.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "a rider doesn't enter a SKIP area")
        core.setMounted(p, false)
        assertEquals(listOf("enter road p MOVE"), sink.take(), "dismounting inside enters on foot")
    }

    @Test
    fun mountingInsideASkipAreaKeepsTheMembershipUntilLeaving() {
        f.area("A", 10, 60, 0, 20, 70, 5)
        val p = f.join("p", 15.5, 64.0, 2.5)
        core.setMounted(p, true)
        f.move(p, 16.5, 64.0, 2.5)
        f.move(p, 25.5, 64.0, 2.5)
        assertEquals(listOf("enter A p JOIN", "exit A p VEHICLE"), sink.take())
    }

    @Test
    fun allowAreaEntersRidersWithVehicleCause() {
        f.area("toll", 10, 60, 0, 20, 70, 5, allow)
        val p = f.join("p", 0.5, 64.0, 2.5, mounted = true)
        f.move(p, 15.5, 64.0, 2.5)
        f.move(p, 25.5, 64.0, 2.5)
        assertEquals(listOf("enter toll p VEHICLE", "exit toll p VEHICLE"), sink.take())
    }

    @Test
    fun onlyAreaEntersOnMountAndKeepsDismountedMembers() {
        f.area("lane", 10, 60, 0, 20, 70, 5, only)
        val p = f.join("p", 0.5, 64.0, 2.5)
        f.move(p, 15.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "on foot doesn't enter an ONLY area")
        core.setMounted(p, true)
        assertEquals(listOf("enter lane p VEHICLE"), sink.take())
        core.setMounted(p, false)
        f.move(p, 16.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "dismounting inside keeps the membership")
        f.move(p, 30.5, 64.0, 2.5)
        assertEquals(listOf("exit lane p MOVE"), sink.take())
    }

    @Test
    fun dismountWithoutMovingSeesAreasRegisteredSinceTheLastStep() {
        // Regression (Codex M2 #2): the cached chunk areas were used without checking the index version.
        val p = f.join("p", 2.5, 64.0, 2.5, mounted = true) // empty chunk at join
        f.area("A", 0, 60, 0, 5, 70, 5) // SKIP: the rider doesn't enter
        assertEquals(emptyList<String>(), sink.take())
        core.setMounted(p, false)
        assertEquals(listOf("enter A p MOVE"), sink.take())
    }

    @Test
    fun leavingSpectatorWithoutMovingSeesAreasRegisteredSinceTheLastStep() {
        val p = f.join("p", 2.5, 64.0, 2.5)
        core.setSpectator(p, true)
        f.area("A", 0, 60, 0, 5, 70, 5)
        assertEquals(emptyList<String>(), sink.take())
        core.setSpectator(p, false)
        assertEquals(listOf("enter A p MOVE"), sink.take())
    }

    @Test
    fun spectatorsDontEnterUnlessIncluded() {
        f.area("plain", 0, 60, 0, 5, 70, 5)
        f.area("spec", 0, 60, 0, 5, 70, 5, AreaOptions.builder().includeSpectators(true).build())
        val p = f.join("p", 20.5, 64.0, 2.5)
        core.setSpectator(p, true)
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter spec p MOVE"), sink.take())
        core.setSpectator(p, false)
        assertEquals(listOf("enter plain p MOVE"), sink.take(), "leaving spectator inside enters on the spot")
    }

    // ---------------------------------------------------------------- pass-through

    @Test
    fun passThroughFiresEnterThenExitOnAFastSegment() {
        f.area("gate", 20, 60, 0, 21, 70, 1, pt)
        f.area("plain", 23, 60, 0, 24, 70, 1)
        val p = f.join("p", 10.5, 64.0, 0.5)
        f.move(p, 30.5, 64.0, 0.5)
        assertEquals(listOf("enter gate p MOVE", "exit gate p MOVE"), sink.take(), "a plain area crossed in one move fires nothing")
        assertEquals(1L, core.passThroughs)
    }

    @Test
    fun passThroughInAChunkBetweenTwoEmptyChunks() {
        // Start in chunk 1 and end in chunk 3: neither has areas or watches, so only the path walk can find the gate.
        f.area("gate", 40, 60, 0, 41, 70, 1, pt)
        val p = f.join("p", 20.5, 64.0, 0.5)
        f.move(p, 60.5, 64.0, 0.5)
        assertEquals(listOf("enter gate p MOVE", "exit gate p MOVE"), sink.take())
        f.move(p, 60.5, 64.0, 30.5)
        f.move(p, 20.5, 64.0, 30.5)
        assertEquals(emptyList<String>(), sink.take(), "a path that misses the gate")
    }

    @Test
    fun passThroughOnADiagonalAcrossAChunkCorner() {
        f.area("corner", 15, 60, 15, 16, 70, 16, pt)
        val p = f.join("p", 8.5, 64.0, 8.5)
        f.move(p, 24.5, 64.0, 24.5)
        assertEquals(listOf("enter corner p MOVE", "exit corner p MOVE"), sink.take())
    }

    @Test
    fun passThroughRespectsRidersAndTeleports() {
        f.area("gate", 20, 60, 0, 21, 70, 1, pt) // riders SKIP
        f.area("tollgate", 20, 60, 2, 21, 70, 3, pt.toBuilder().riders(RiderPolicy.ALLOW).build())
        val p = f.join("p", 10.5, 64.0, 1.5, mounted = true)
        f.move(p, 30.5, 64.0, 2.5)
        assertEquals(listOf("enter tollgate p VEHICLE", "exit tollgate p VEHICLE"), sink.take())
        f.teleport(p, 10.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "a teleport has no path")
    }

    @Test
    fun customAreaPassThroughSamplesThePredicate() {
        // A disc of radius 3 around (40.5, 0.5), boxed in 36..45.
        val disc = Area.custom(f.world, 36, 60, -4, 45, 70, 5) { x, _, z ->
            (x - 40.5) * (x - 40.5) + (z - 0.5) * (z - 0.5) <= 9.0
        }
        f.add("disc", disc, pt)
        val p = f.join("p", 30.5, 64.0, 0.5)
        f.move(p, 50.5, 64.0, 0.5)
        assertEquals(listOf("enter disc p MOVE", "exit disc p MOVE"), sink.take(), "through the centre")
        // Across the box's corner (x >= 36, z >= -4 for t in [0.3, 0.5]) but never within 3 of the centre.
        f.teleport(p, 34.5, 64.0, -1.5)
        f.move(p, 39.5, 64.0, -6.5)
        assertEquals(emptyList<String>(), sink.take())
    }

    // ---------------------------------------------------------------- watches

    @Test
    fun stepWatchFiresOnFootAndPausesWhileRiding() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.stepWatch("w", p)
        f.move(p, 1.5, 64.0, 0.5)
        core.setMounted(p, true)
        f.move(p, 2.5, 64.0, 0.5)
        core.setMounted(p, false)
        f.move(p, 3.5, 64.0, 0.5)
        f.teleport(p, 50.5, 64.0, 0.5)
        assertEquals(listOf("step w p 0,64,0->1,64,0", "step w p 2,64,0->3,64,0"), sink.take())
    }

    @Test
    fun stepWatchWithTeleportsAndRiders() {
        val p = f.join("p", 0.5, 64.0, 0.5, mounted = true)
        f.stepWatch("w", p, WatchOptions.builder().onFootOnly(false).includeTeleports(true).build())
        f.move(p, 1.5, 64.0, 0.5)
        f.teleport(p, 50.5, 64.0, 0.5)
        f.move(p, 50.5, 64.0, 0.5, f.nether)
        assertEquals(
            listOf("step w p 0,64,0->1,64,0 mounted", "step w p 1,64,0->50,64,0 mounted", "step w p 50,64,0->50,64,0 mounted"),
            sink.take(),
        )
    }

    @Test
    fun seatedPlayerGetsOneStepPerBlockFromPollAndMoveEventAndKeepsPositionOnDismount() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.stepWatch("w", p, WatchOptions.builder().onFootOnly(false).build())
        core.setMounted(p, true)
        // The mounted poll and PlayerMoveEvent both report the seat's block: one step.
        core.position(p, f.world, 5.5, 64.4, 0.5, false) // poll
        core.position(p.uuid, f.world, 5.3, 64.4, 0.7, false) // move event, same block
        assertEquals(listOf("step w p 0,64,0->5,64,0 mounted"), sink.take())
        core.setMounted(p, false)
        assertEquals(5, p.bx, "dismount keeps the position")
        f.move(p, 5.9, 64.0, 0.5) // same block after dismount: nothing
        f.move(p, 6.5, 64.0, 0.5)
        assertEquals(listOf("step w p 5,64,0->6,64,0"), sink.take())
    }

    @Test
    fun keepOnWorldChangeAndKeepOnQuit() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        val leaves = f.stepWatch("leaves", p, WatchOptions.builder().keepOnWorldChange(false).build())
        val stays = f.stepWatch("stays", p)
        val kept = f.stepWatch("kept", p, WatchOptions.builder().keepOnQuit(true).build())
        f.move(p, 0.5, 64.0, 0.5, f.nether)
        assertFalse(leaves.active)
        assertEquals(listOf<Watch>(leaves), sink.ended)
        core.quit(p.uuid)
        assertFalse(stays.active)
        assertTrue(kept.active)
        val again = f.join("p", 0.5, 64.0, 0.5)
        f.move(again, 1.5, 64.0, 0.5)
        assertEquals(listOf("step kept p 0,64,0->1,64,0"), sink.take().filter { it.startsWith("step") })
    }

    @Test
    fun blockWatchAtTheFeetReportsEnteredStayedLeft() {
        sink.blocks[Triple(3, 64, 0)] = Material.WATER
        sink.blocks[Triple(3, 64, 1)] = Material.WATER
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.blockWatch("water", p, BlockOffset.FEET, Material.WATER)
        f.move(p, 3.5, 64.0, 0.5)
        f.move(p, 3.5, 64.0, 1.5)
        f.move(p, 3.5, 64.0, 2.5)
        f.move(p, 3.5, 64.0, 3.5)
        assertEquals(
            listOf("block water p 3,64,0 ENTERED", "block water p 3,64,1 STAYED", "block water p 3,64,2 LEFT"),
            sink.take(),
        )
    }

    @Test
    fun blockUnderFindsFenceTopsSlabsAndFullBlocks() {
        sink.blocks[Triple(5, 63, 0)] = Material.OAK_FENCE // fence top at y 64.5
        sink.blocks[Triple(6, 64, 0)] = Material.OAK_SLAB // bottom slab: feet at 64.5
        sink.blocks[Triple(7, 63, 0)] = Material.STONE // full block: feet at 64.0
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.blockWatch("u", p, BlockOffset.BELOW, Material.OAK_FENCE, Material.OAK_SLAB, Material.STONE, reportLeft = false)
        f.move(p, 5.5, 64.5, 0.5)
        f.move(p, 6.5, 64.5, 0.5)
        f.move(p, 7.5, 64.0, 0.5)
        f.move(p, 8.5, 64.0, 0.5) // air under: nothing (block-under reports no LEFT)
        assertEquals(
            listOf("block u p 5,63,0 ENTERED", "block u p 6,64,0 STAYED", "block u p 7,63,0 STAYED"),
            sink.take(),
        )
    }

    @Test
    fun blocksAreReadOnlyForWatchedPlayersOnBlockChanges() {
        var reads = 0
        val counting = object : EngineSink by sink {
            override fun typeAt(state: PlayerState, x: Int, y: Int, z: Int): Material {
                reads++
                return sink.typeAt(state, x, y, z)
            }
        }
        val c = MovementCore(AreaIndex(), counting)
        val watched = c.join(java.util.UUID.randomUUID(), null, f.world, 0.5, 64.0, 0.5, false, false)
        val other = c.join(java.util.UUID.randomUUID(), null, f.world, 0.5, 64.0, 0.5, false, false)
        c.addWatch(BlockWatch(watched.uuid, BlockOffset.FEET, setOf(Material.WATER), null, true, WatchOptions.DEFAULT))
        for (i in 1..10) {
            c.position(other, f.world, i + 0.5, 64.0, 0.5, false)
            c.position(watched, f.world, i + 0.5, 64.0, 0.5, false)
            c.position(watched, f.world, i + 0.7, 64.0, 0.5, false) // same block
        }
        assertEquals(10, reads)
    }

    // ---------------------------------------------------------------- runtime areas

    @Test
    fun areaAddedAtRuntimeIsSeenWithoutAPlayerScan() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.move(p, 1.5, 64.0, 0.5) // caches chunk (0,0) with no areas
        f.area("late", 5, 60, 0, 6, 70, 1)
        f.move(p, 5.5, 64.0, 0.5) // same chunk: the version change makes it re-read
        assertEquals(listOf("enter late p MOVE"), sink.take())
    }

    @Test
    fun registeringAroundPlayersAnnouncesOrCountsSilently() {
        val p = f.join("p", 2.5, 64.0, 2.5)
        f.area("loud", 0, 60, 0, 5, 70, 5, AreaOptions.builder().fireOnRegister(true).build())
        f.area("quiet", 0, 60, 0, 5, 70, 5)
        assertEquals(listOf("enter loud p REGISTER"), sink.take())
        f.move(p, 20.5, 64.0, 2.5)
        assertEquals(listOf("exit loud p MOVE"), sink.take())
    }

    @Test
    fun closingAnAreaExitsAnnouncedMembersOnly() {
        val a = f.area("A", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 2.5, 64.0, 2.5)
        val q = f.join("q", 20.5, 64.0, 2.5)
        val silent = f.area("S", 0, 60, 0, 5, 70, 5)
        sink.take()
        core.removeArea(a, false)
        core.removeArea(silent, false)
        assertEquals(listOf("exit A p UNREGISTER"), sink.take())
        assertEquals(0, p.memberCount)
        assertEquals(0, a.members)
        f.move(q, 2.5, 64.0, 2.5)
        assertEquals(emptyList<String>(), sink.take(), "a closed area is gone from the index")
    }

    @Test
    fun anAreaClosedAndReRegisteredWithTheSameBoundsIsSeenFreshWithoutAChunkChange() {
        // The player's chunk cache is keyed by the index version, not by bounds: the old registration must not linger.
        val a = f.area("A", 0, 60, 0, 9, 70, 9)
        val p = f.join("p", 2.5, 64.0, 2.5)
        core.removeArea(a, false)
        f.area("B", 0, 60, 0, 9, 70, 9, AreaOptions.builder().fireOnRegister(true).stepEvents(true).build())
        f.move(p, 3.5, 64.0, 2.5) // same chunk: must read the new array
        f.move(p, 12.5, 64.0, 2.5) // still chunk 0: exits B only
        assertEquals(listOf("enter A p JOIN", "exit A p UNREGISTER", "enter B p REGISTER", "inside B p MOVE", "exit B p MOVE"), sink.take())
        assertSame(null, p.chunkAreas?.firstOrNull { it === a }, "the closed registration is gone from the cache")
    }

    // ---------------------------------------------------------------- coordinates

    @Test
    fun farCoordinatesAndBuildLimits() {
        f.area("far", 30_000_100, 60, -30_000_102, 30_000_102, 70, -30_000_100)
        f.area("bottom", 0, -64, 0, 0, -64, 0)
        f.area("top", 0, 319, 0, 0, 319, 0)
        f.area("deep", 0, -2032, 0, 0, -2032, 0)
        val p = f.join("p", 30_000_090.5, 64.0, -30_000_101.5)
        f.move(p, 30_000_101.5, 64.0, -30_000_101.5)
        f.teleport(p, 0.5, -63.5, 0.5)
        f.teleport(p, 0.5, 319.2, 0.5)
        f.teleport(p, 0.5, -2031.9, 0.5)
        f.teleport(p, 0.5, 3000.0, 0.5) // above any build limit: just outside everything
        assertEquals(
            listOf(
                "enter far p MOVE", "exit far p TELEPORT", "enter bottom p TELEPORT", "exit bottom p TELEPORT",
                "enter top p TELEPORT", "exit top p TELEPORT", "enter deep p TELEPORT", "exit deep p TELEPORT",
            ),
            sink.take(),
        )
    }

    @Test
    fun throwingPredicateCountsAsOutsideAndDoesntStopOthers() {
        f.add("bad", Area.custom(f.world, 0, 60, 0, 5, 70, 5) { _, _, _ -> error("boom") })
        f.area("good", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter good p MOVE"), sink.take())
        assertEquals(1, sink.predicateFailures)
    }
}
