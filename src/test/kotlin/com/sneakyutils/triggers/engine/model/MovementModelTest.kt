package com.sneakyutils.triggers.engine.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the movement engine: random operation sequences run on the real engine core
 * ([com.sneakyutils.triggers.engine.MovementCore]) and on a naive reference model of the documented semantics
 * ([ReferenceModel]); after every operation the delivered events (per player and subscription, in order) and the
 * state (positions, memberships, watches, counts) must agree.
 *
 * Sequences cover moves of every length (one block, vehicles, through chunk corners, along box faces, exactly the
 * 64-block limit and beyond), teleports, world changes, joins, quits, deaths, respawns, mounting, spectator mode,
 * areas and watches added and removed (also from inside handlers), and handlers that teleport, kick, kill, mount,
 * subscribe or close. Playgrounds include the world border, the BlockKey packing limit, the int limit and both y
 * extremes.
 *
 * - CI runs [CI_SEQUENCES] sequences from a fixed seed.
 * - `-Dtriggers.soak=N` runs N sequences; `-Dtriggers.seed=S` changes the base seed.
 * - `-Dtriggers.replay=S` runs the one sequence with seed S (as printed by a failure).
 *
 * A failure prints the sequence seed, the shrunk operation list and the first difference.
 */
class MovementModelTest {

    @Test
    fun randomSequencesMatchTheReferenceModel() {
        val replay = System.getProperty("triggers.replay")?.takeIf { it.isNotBlank() }?.toLong()
        if (replay != null) {
            runSequence(replay)?.let { throw AssertionError(it) }
            return
        }
        val soak = System.getProperty("triggers.soak")?.takeIf { it.isNotBlank() }?.toLong()
        val base = System.getProperty("triggers.seed")?.takeIf { it.isNotBlank() }?.toLong() ?: DEFAULT_SEED
        val count = soak ?: CI_SEQUENCES
        val t0 = System.nanoTime()
        var ops = 0L
        val coverage = java.util.TreeMap<String, Long>()
        for (i in 0 until count) {
            val seed = mix(base, i)
            val failure = runSequence(seed, coverage) { ops++ }
            if (failure != null) throw AssertionError("sequence $i of base seed $base\n$failure")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("MovementModelTest: $count sequences, $ops operations, base seed $base, $ms ms")
        println("  coverage: " + coverage.entries.joinToString(", ") { "${it.key}=${it.value}" })
        assertTrue(ops > count * 20, "sequences are long enough to matter")
        // The generator must keep producing the interesting cases, or the comparison proves little.
        for (k in MUST_COVER) assertTrue((coverage[k] ?: 0L) > count / 100, "too few '$k' in $coverage")
    }

    /** Runs one generated sequence; returns a failure report (with the shrunk sequence) or null. */
    private fun runSequence(seed: Long, coverage: MutableMap<String, Long>? = null, counted: () -> Unit = {}): String? {
        val gen = Generator(seed)
        val runner = Runner(coverage)
        val ops = ArrayList<Op>()
        var failure: String? = null
        val length = 20 + (seed ushr 1).mod(60L).toInt()
        gen.sequence(runner.model, length) { op ->
            ops += op
            counted()
            failure = runner.step(op)
            failure == null
        }
        val f = failure ?: return null
        val shrunk = Runner.shrink(ops)
        val again = Runner.replay(shrunk)?.second ?: f
        return buildString {
            appendLine("Engine and model disagree. Sequence seed $seed (replay: -Dtriggers.replay=$seed), playground " +
                gen.origin.joinToString(", ", "(", ")"))
            appendLine("Shrunk from ${ops.size} to ${shrunk.size} operations:")
            for ((i, op) in shrunk.withIndex()) appendLine("  ${i + 1}. $op")
            appendLine("Difference after the last one: $again")
        }
    }

    /** Shrunk sequences of past disagreements, replayed every run. */
    @Test
    fun foundDisagreementsStayFixed() {
        val cases = mapOf(
            // Soak, base seed 777, sequence 22144: a move exactly through a box corner fired a pass-through (the
            // slab test saw a one-ulp crossing). Fixed with Geometry.MIN_CROSSING.
            "corner touch is no crossing" to listOf(
                Op.Join(0, 0, 36.15625, 63.90625, -46.875, mounted = false, spectator = false),
                Op.AddArea(4, AreaSpec(0, -7, 60, -11, -1, 63, -1, com.sneakyutils.triggers.RiderPolicy.ALLOW,
                    spectators = false, passThrough = true, fireOnJoin = true, fireOnRegister = false, stepEvents = false)),
                Op.Pos(0, 0, -4.453924901599332, 61.0, 1.0, teleport = false),
                Op.Pos(0, 0, 2.226962450799666, 61.0, -0.5, teleport = false),
            ),
        )
        for ((name, ops) in cases) {
            val f = Runner.replay(ops)
            assertTrue(f == null, "$name: ${f?.second}")
        }
    }

    @Test
    fun theModelsPathTestIsExact() {
        val wall = AreaSpec(0, 16, 0, 0, 16, 10, 31, com.sneakyutils.triggers.RiderPolicy.ALLOW, false, true, true, false, false)
        assertTrue(ReferenceModel.crosses(wall, 10.0, 5.0, 5.0, 20.0, 5.0, 5.0))
        assertTrue(!ReferenceModel.crosses(wall, 10.0, 5.0, 5.0, 16.0, 5.0, 5.0), "stops on the face: zero length")
        assertTrue(!ReferenceModel.crosses(wall, 17.0, 5.0, 5.0, 30.0, 5.0, 5.0), "runs along the far face")
        // Across the wall's vertical edge at x 16, z 0: in only at that point (zero length).
        assertEquals(false, ReferenceModel.crosses(wall, 15.0, 5.0, 1.0, 17.0, 5.0, -1.0))
        // The same line shifted a 1/64 block into the wall crosses it.
        assertEquals(true, ReferenceModel.crosses(wall, 15.0, 5.0, 1.015625, 17.0, 5.0, -0.984375))
        // Exactly through the corner diagonal (16, 0) going in: positive length inside.
        assertEquals(true, ReferenceModel.crosses(wall, 15.0, 5.0, -1.0, 17.0, 5.0, 1.0))
    }

    companion object {
        const val DEFAULT_SEED = 20261115L
        const val CI_SEQUENCES = 10_000L

        private val MUST_COVER = listOf(
            "ENTER MOVE", "ENTER VEHICLE", "ENTER TELEPORT", "ENTER JOIN", "ENTER WORLD_CHANGE", "ENTER REGISTER",
            "EXIT MOVE", "EXIT VEHICLE", "EXIT TELEPORT", "EXIT QUIT", "EXIT WORLD_CHANGE", "EXIT UNREGISTER", "EXIT DEATH",
            "AREA_STEP MOVE", "AREA_STEP VEHICLE", "AREA_STEP TELEPORT", "STEP", "STEP mounted",
            "BLOCK ENTERED", "BLOCK STAYED", "BLOCK LEFT", "enter+exit pairs (pass-through)", "hooks fired",
            "self-triggered skips", "predicate meddling refused",
        )
    }
}
