package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.engine.Geometry.chunkKey
import com.sneakyutils.triggers.engine.Geometry.segmentChunks
import com.sneakyutils.triggers.engine.Geometry.segmentHitsBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor

class GeometryTest {

    private fun chunks(x0: Double, z0: Double, x1: Double, z1: Double, cap: Int = 32): List<Pair<Int, Int>> {
        val out = LongArray(cap)
        val n = segmentChunks(x0, z0, x1, z1, out)
        return (0 until n).map { Geometry.chunkX(out[it]) to Geometry.chunkZ(out[it]) }
    }

    /** Brute force: chunks of points sampled densely along the move. */
    private fun sampled(x0: Double, z0: Double, x1: Double, z1: Double): Set<Pair<Int, Int>> {
        val set = LinkedHashSet<Pair<Int, Int>>()
        val steps = 20_000
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = x0 + (x1 - x0) * t
            val z = z0 + (z1 - z0) * t
            set += (floor(x).toInt() shr 4) to (floor(z).toInt() shr 4)
        }
        return set
    }

    @Test
    fun blockOfAgreesWithBukkitsLocationBlockAtTheBorderAndTheIntLimits() {
        // The listener drops a move whose Location.blockX/Y/Z didn't change, the engine compares blockOf(): if they
        // disagreed anywhere, a block step would be lost or doubled.
        val edges = doubleArrayOf(0.0, -0.0, 0.5, -0.5, 1.0e-300, -1.0e-300, Math.nextDown(1.0), Math.nextUp(-1.0),
            30_000_000.0, Math.nextDown(30_000_000.0), -30_000_000.0, Math.nextDown(-30_000_000.0),
            29_999_999.999999996, -29_999_999.999999996, 33_554_431.5, -33_554_432.5,
            2_147_483_647.0, 2_147_483_646.999999, -2_147_483_648.0, -2_147_483_647.5)
        for (v in edges) assertEquals(org.bukkit.Location.locToBlock(v), Geometry.blockOf(v), "at $v")
        val r = java.util.SplittableRandom(30)
        repeat(1_000_000) {
            val v = when (it % 4) {
                0 -> (r.nextDouble() * 2 - 1) * 30_000_000.0
                1 -> (r.nextDouble() * 2 - 1) * 2_000_000_000.0
                2 -> r.nextInt(-30_000_000, 30_000_000) + (if (r.nextBoolean()) 0.0 else Math.ulp(30_000_000.0) * r.nextInt(-3, 4))
                else -> (r.nextDouble() * 2 - 1) * 4096.0
            }
            assertEquals(org.bukkit.Location.locToBlock(v), Geometry.blockOf(v), "at $v")
        }
    }

    @Test
    fun chunkKeyRoundTripsEveryCorner() {
        for (x in intArrayOf(0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE, 1_875_000, -1_875_001)) {
            for (z in intArrayOf(0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE, 1_875_000, -1_875_001)) {
                val k = chunkKey(x, z)
                assertEquals(x, Geometry.chunkX(k))
                assertEquals(z, Geometry.chunkZ(k))
            }
        }
        // Neighbours never collide, including across the sign boundary.
        assertTrue(chunkKey(-1, 0) != chunkKey(0, -1))
        assertTrue(chunkKey(0, -1) != chunkKey(-1, -1))
    }

    @Test
    fun negativeCoordinatesMapToTheRightChunk() {
        assertEquals(listOf(-1 to -1), chunks(-0.5, -0.5, -15.9, -15.9))
        assertEquals(listOf(-1 to 0, 0 to 0), chunks(-0.1, 3.0, 0.1, 3.0))
        assertEquals(listOf(-2 to 0), chunks(-17.0, 0.0, -17.0, 0.0))
    }

    @Test
    fun straightMovesAcrossSeveralChunks() {
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0), chunks(1.0, 1.0, 65.0, 1.0))
        assertEquals(listOf(0 to 0, 0 to -1, 0 to -2), chunks(5.0, 5.0, 5.0, -20.0))
    }

    @Test
    fun diagonalMovesVisitEverySampledChunkInOrder() {
        val cases = listOf(
            doubleArrayOf(3.3, 7.7, 60.1, 41.9),
            doubleArrayOf(-3.3, -7.7, -60.1, 41.9),
            doubleArrayOf(100.25, -30.5, 52.0, 10.75),
            doubleArrayOf(0.5, 0.5, 63.5, 1.5),
        )
        for (c in cases) {
            val got = chunks(c[0], c[1], c[2], c[3])
            val expected = sampled(c[0], c[1], c[2], c[3])
            assertTrue(got.containsAll(expected), "missed chunks for ${c.toList()}: got $got, expected $expected")
            assertEquals(expected.first(), got.first())
            assertEquals(expected.last(), got.last())
        }
    }

    @Test
    fun exactChunkCornerListsBothSideChunks() {
        // Passes exactly through the corner (16, 16).
        val got = chunks(8.0, 8.0, 24.0, 24.0)
        assertEquals(0 to 0, got.first())
        assertEquals(1 to 1, got.last())
        assertTrue(got.containsAll(listOf(1 to 0, 0 to 1)), "both side chunks of the corner: $got")
        // Negative corner (0, 0) from the south-east going north-west.
        val neg = chunks(8.0, 8.0, -8.0, -8.0)
        assertEquals(0 to 0, neg.first())
        assertEquals(-1 to -1, neg.last())
        assertTrue(neg.containsAll(listOf(-1 to 0, 0 to -1)))
    }

    @Test
    fun startingOnABoundaryGoingNegative() {
        // x = 16.0 is the first block of chunk 1; moving west crosses into chunk 0 immediately.
        assertEquals(listOf(1 to 0, 0 to 0), chunks(16.0, 4.0, 15.0, 4.0))
    }

    @Test
    fun coordinatesBeyondThirtyMillion() {
        val x0 = 30_000_010.5
        val got = chunks(x0, -30_000_020.5, x0 + 40.0, -30_000_020.5 + 40.0)
        val expected = sampled(x0, -30_000_020.5, x0 + 40.0, -30_000_020.5 + 40.0)
        assertTrue(got.containsAll(expected), "got $got expected $expected")
        assertEquals((30_000_010 shr 4) to (-30_000_021 shr 4), got.first())
        // Int-sized chunk coordinates far past the border still work.
        val far = chunks(2.0e9, 0.0, 2.0e9 + 20.0, 0.0)
        assertEquals((2_000_000_000 shr 4), far.first().first)
        assertEquals((2_000_000_020 shr 4), far.last().first)
    }

    @Test
    fun cappedOutputAlwaysEndsWithTheEndChunk() {
        val got = chunks(0.5, 0.5, 1000.5, 0.5, cap = 4)
        assertEquals(4, got.size)
        assertEquals(0 to 0, got.first())
        assertEquals((1000 shr 4) to 0, got.last())
    }

    @Test
    fun sixtyFourBlockMovesFitTheEngineBuffer() {
        // The engine's buffer is 16 keys; any 64-block move must fit without capping.
        var worst = 0
        val rnd = java.util.Random(7)
        repeat(20_000) {
            val x0 = rnd.nextDouble() * 64 - 32
            val z0 = rnd.nextDouble() * 64 - 32
            val angle = rnd.nextDouble() * Math.PI * 2
            val x1 = x0 + Math.cos(angle) * 64
            val z1 = z0 + Math.sin(angle) * 64
            val out = LongArray(64)
            worst = maxOf(worst, segmentChunks(x0, z0, x1, z1, out))
        }
        assertTrue(worst <= 16, "worst case $worst chunk keys")
    }

    @Test
    fun slabTestHitsMissesAndEdges() {
        // Box covering blocks 10..12 on x and z, 64..66 on y: continuous [10, 13).
        fun hit(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
            segmentHitsBox(x0, y0, z0, x1, y1, z1, 10, 64, 10, 12, 66, 12)

        assertTrue(hit(0.0, 65.0, 11.0, 20.0, 65.0, 11.0), "straight through")
        assertTrue(hit(0.0, 65.0, 0.0, 20.0, 65.0, 20.0), "diagonal through")
        assertFalse(hit(0.0, 65.0, 0.0, 9.9, 65.0, 9.9), "stops short")
        assertFalse(hit(0.0, 70.0, 11.0, 20.0, 70.0, 11.0), "above")
        assertFalse(hit(0.0, 65.0, 13.0, 20.0, 65.0, 13.0), "along the far face (z = 13 is outside)")
        assertTrue(hit(0.0, 65.0, 10.0, 20.0, 65.0, 10.0), "along the near face (z = 10 is inside)")
        assertFalse(hit(14.0, 65.0, 11.0, 12.0, 65.0, 9.0), "touches only the corner (13, 10)")
        assertTrue(hit(11.0, 65.0, 11.0, 11.0, 65.0, 11.0), "zero-length inside")
        assertFalse(hit(9.0, 65.0, 11.0, 9.0, 65.0, 11.0), "zero-length outside")
        assertTrue(hit(11.5, 65.0, 11.5, 30.0, 65.0, 11.5), "starts inside")
        assertTrue(hit(11.0, 100.0, 11.0, 11.0, 0.0, 11.0), "vertical through")
    }

    @Test
    fun slabTestReturnsTheRange() {
        val r = DoubleArray(2)
        assertTrue(segmentHitsBox(0.0, 65.0, 11.0, 20.0, 65.0, 11.0, 10, 64, 10, 12, 66, 12, r))
        assertEquals(0.5, r[0], 1e-12)
        assertEquals(0.65, r[1], 1e-12)
    }

    @Test
    fun aPathExactlyThroughABoxCornerDoesNotCrossItDespiteRounding() {
        // Found by MovementModelTest (soak, base seed 777): the move is exactly collinear with the corner (0, 0) of
        // the box's x and z faces, but dx = 1.5 * 4.45... is not a double, so the slab test saw a crossing about one
        // ulp long and fired a pass-through for a corner touch.
        val x0 = -4.453924901599332
        val x1 = 2.226962450799666
        assertFalse(segmentHitsBox(x0, 61.0, 1.0, x1, 61.0, -0.5, -7, 60, -11, -1, 63, -1))
        // A real crossing of that corner, however small in blocks, still counts.
        assertTrue(segmentHitsBox(x0, 61.0, 1.0, x1, 61.0, -0.51, -7, 60, -11, -1, 63, -1))
    }

    @Test
    fun slabTestAtLargeAndNegativeCoordinates() {
        assertTrue(segmentHitsBox(-30_000_100.0, -60.0, -5.5, -29_999_900.0, -60.0, -5.5,
            -30_000_001, -64, -6, -30_000_000, -59, -5))
        assertFalse(segmentHitsBox(-30_000_100.0, -60.0, -4.0, -29_999_900.0, -60.0, -4.0,
            -30_000_001, -64, -6, -30_000_000, -59, -5))
        // Build limits: a box at the very top of the world.
        assertTrue(segmentHitsBox(0.5, 330.0, 0.5, 0.5, 300.0, 0.5, 0, 319, 0, 0, 319, 0))
        assertTrue(segmentHitsBox(0.5, -70.0, 0.5, 0.5, -60.0, 0.5, 0, -64, 0, 0, -64, 0))
    }
}
