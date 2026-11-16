package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.WatchOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Handlers that call back into the engine while it delivers: teleport, kick, subscribe, close. Every case checks
 * that enter and exit still alternate per player and area.
 */
class ReentrancyTest {
    private val f = EngineFixture()
    private val core = f.core
    private val sink = f.sink

    @Test
    fun handlerTeleportsThePlayerAwayDuringEnter() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        f.area("B", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        sink.hook = { if (it == "enter A p MOVE") f.teleport(p, 100.5, 64.0, 2.5) }
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p MOVE", "enter B p MOVE", "exit A p TELEPORT", "exit B p TELEPORT"), sink.take())
        assertEquals(100, p.bx)
        assertEquals(0, p.memberCount)
    }

    @Test
    fun handlerKicksThePlayerDuringEnter() {
        val a = f.area("A", 0, 60, 0, 5, 70, 5)
        val b = f.area("B", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        sink.hook = { if (it == "enter A p MOVE") core.quit(p.uuid) }
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p MOVE", "enter B p MOVE", "exit A p QUIT", "exit B p QUIT"), sink.take(), "exits in the order entered")
        assertEquals(0, a.members + b.members)
    }

    @Test
    fun queuedStepsForAPlayerWhoQuitAreSkipped() {
        f.area("S", 0, 60, 0, 9, 70, 9, AreaOptions.builder().stepEvents(true).build())
        val p = f.join("p", 2.5, 64.0, 2.5)
        f.stepWatch("w", p)
        sink.take()
        sink.hook = { if (it.startsWith("step w")) core.quit(p.uuid) }
        f.move(p, 3.5, 64.0, 2.5)
        assertEquals(listOf("step w p 2,64,2->3,64,2", "exit S p QUIT"), sink.take(), "the queued 'inside S' is skipped")
    }

    @Test
    fun handlerRegistersAnAreaAroundThePlayer() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        sink.hook = {
            if (it == "enter A p MOVE") f.area("C", 0, 60, 0, 5, 70, 5, AreaOptions.builder().fireOnRegister(true).build())
        }
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p MOVE", "enter C p REGISTER"), sink.take())
        sink.hook = null
        f.move(p, 30.5, 64.0, 2.5)
        assertEquals(setOf("exit A p MOVE", "exit C p MOVE"), sink.take().toSet())
    }

    @Test
    fun handlerClosesAnotherAreaWhoseEnterIsAlreadyQueued() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        val b = f.area("B", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        sink.hook = { if (it == "enter A p MOVE") core.removeArea(b, false) }
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p MOVE", "enter B p MOVE", "exit B p UNREGISTER"), sink.take(), "enter and exit pair up")
    }

    @Test
    fun silencedAreaDropsItsQueuedEvents() {
        f.area("A", 0, 60, 0, 5, 70, 5)
        val b = f.area("B", 0, 60, 0, 5, 70, 5)
        val p = f.join("p", 20.5, 64.0, 2.5)
        sink.hook = { if (it == "enter A p MOVE") core.removeArea(b, true) } // owner disabled mid-dispatch
        f.move(p, 2.5, 64.0, 2.5)
        assertEquals(listOf("enter A p MOVE"), sink.take())
        assertEquals(0, b.members)
    }

    @Test
    fun watchAddedInAHandlerStartsAtTheNextStepAndOneClosedStopsAtOnce() {
        val p = f.join("p", 0.5, 64.0, 0.5)
        f.stepWatch("w1", p)
        val w3 = f.stepWatch("w3", p)
        sink.hook = {
            if (it.startsWith("step w1") && sink.names.values.none { n -> n == "w2" }) {
                f.stepWatch("w2", p)
                core.removeWatch(w3)
            }
        }
        f.move(p, 1.5, 64.0, 0.5)
        assertEquals(listOf("step w1 p 0,64,0->1,64,0"), sink.take())
        f.move(p, 2.5, 64.0, 0.5)
        assertEquals(listOf("step w1 p 1,64,0->2,64,0", "step w2 p 1,64,0->2,64,0"), sink.take())
    }

    @Test
    fun aStepHandlerThatMovesItsOwnPlayerIsNotCalledForTheMoveItCaused() {
        // A jump pad: the step watch's handler moves the player, which is another step. MagicSpells dropped a passive
        // re-triggered during its own run; the queue used to deliver it again and again, up to the drain cap.
        val p = f.join("p", 0.5, 64.0, 0.5)
        val pad = WatchOptions.builder().includeTeleports(true).build()
        f.stepWatch("pad", p, pad)
        f.stepWatch("other", p, pad)
        var x = 1.5
        sink.hook = { if (it.startsWith("step pad")) { x += 1.0; f.move(p, x, 64.0, 0.5) } }
        f.move(p, 1.5, 64.0, 0.5)
        assertEquals(listOf("step pad p 0,64,0->1,64,0", "step other p 0,64,0->1,64,0", "step other p 1,64,0->2,64,0"),
            sink.take(), "the pad's own move reaches the other watch only")
        assertEquals(0, sink.dropped)
        assertEquals(1L, core.queue.selfTriggerDrops)

        // A chain: each watch moves the player. Every watch hears each move once, then the chain ends.
        sink.hook = { line ->
            if (line.startsWith("step pad") || line.startsWith("step other")) { x += 1.0; f.move(p, x, 64.0, 0.5) }
        }
        f.move(p, 10.5, 64.0, 0.5)
        val chain = sink.take()
        assertEquals(listOf("step pad p 2,64,0->10,64,0", "step other p 2,64,0->10,64,0", "step other p 10,64,0->3,64,0",
            "step pad p 3,64,0->4,64,0"), chain.take(4))
        assertTrue(chain.size < 12, "cut off by the guard, not by the cap: $chain")
        assertEquals(0, sink.dropped)

        // Moves by the same player that no handler caused are always delivered.
        sink.hook = null
        f.move(p, 20.5, 64.0, 0.5)
        f.move(p, 21.5, 64.0, 0.5)
        assertEquals(4, sink.take().size)
    }

    @Test
    fun aHandlerLoopIsCutOffAndTheEngineKeepsWorking() {
        val loop = EngineFixture(maxEvents = 50)
        loop.area("A", 10, 60, 0, 12, 70, 2)
        val p = loop.join("p", 0.5, 64.0, 0.5)
        loop.sink.hook = {
            if (it.startsWith("enter A")) loop.teleport(p, 0.5, 64.0, 0.5)
            if (it.startsWith("exit A")) loop.teleport(p, 11.5, 64.0, 0.5)
        }
        loop.move(p, 11.5, 64.0, 0.5)
        assertEquals(50, loop.sink.take().size)
        assertTrue(loop.sink.dropped > 0)
        loop.sink.hook = null
        loop.area("B", 50, 60, 0, 51, 70, 1)
        loop.teleport(p, 50.5, 64.0, 0.5)
        assertTrue(loop.sink.take().contains("enter B p TELEPORT"), "later events are delivered normally")
    }

    @Test
    fun predicateCallingBackIntoTheEngineIsDroppedOrRejected() {
        lateinit var p: PlayerState
        var nested = 0
        f.add("odd", Area.custom(f.world, 0, 60, 0, 5, 70, 5) { _, _, _ ->
            nested++
            core.position(p, f.world, 200.5, 64.0, 0.5, true) // dropped
            true
        })
        f.add("worse", Area.custom(f.world, 0, 60, 0, 5, 70, 5) { _, _, _ ->
            f.area("illegal", 0, 0, 0, 1, 1, 1) // throws: not allowed during evaluation
            true
        })
        p = f.join("p", 20.5, 64.0, 2.5)
        f.move(p, 2.5, 64.0, 2.5)
        assertTrue(nested > 0)
        assertTrue(core.reentrantDrops > 0)
        assertEquals(2, p.bx, "the nested report didn't move the player")
        assertEquals(listOf("enter odd p MOVE"), sink.take())
        assertEquals(1, sink.predicateFailures, "the illegal registration surfaced as a predicate failure")
    }
}
