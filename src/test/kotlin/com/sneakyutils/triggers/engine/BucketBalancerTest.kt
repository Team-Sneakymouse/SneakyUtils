package com.sneakyutils.triggers.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class BucketBalancerTest {

    private fun loads(b: BucketBalancer) = (0 until b.buckets).map { b.load(it) }

    @Test
    fun freshBalancerFillsBucketsInOrderThenWrapsEvenly() {
        val b = BucketBalancer(20)
        assertEquals(20, b.buckets)
        val first = (0 until 20).map { b.add() }
        assertEquals((0 until 20).toList(), first)
        repeat(15) { b.add() }
        val l = loads(b)
        assertTrue(l.max() - l.min() <= 1, "loads stay within one: $l")
        assertEquals(35, b.total)
    }

    @Test
    fun addAlwaysPicksALeastLoadedBucket() {
        val rnd = Random(1)
        val b = BucketBalancer(37)
        val members = ArrayList<Int>()
        repeat(50_000) {
            if (members.isNotEmpty() && rnd.nextInt(3) == 0) {
                b.remove(members.removeAt(rnd.nextInt(members.size)))
            } else {
                val min = loads(b).min()
                val chosen = b.add()
                assertEquals(min, b.load(chosen) - 1, "chosen bucket was least loaded")
                members += chosen
            }
            assertEquals(loads(b).min(), b.minimumLoad)
        }
        assertEquals(members.size, b.total)
    }

    @Test
    fun removalMakesThatBucketTheNextChoice() {
        val b = BucketBalancer(10)
        repeat(30) { b.add() } // every bucket at 3
        b.remove(7)
        assertEquals(7, b.add())
    }

    @Test
    fun removingFromAnEmptyBucketFails() {
        val b = BucketBalancer(4)
        assertThrows(IllegalStateException::class.java) { b.remove(2) }
    }

    @Test
    fun intervalOneHasOneBucketForEveryone() {
        val b = BucketBalancer(1)
        repeat(5_000) { assertEquals(0, b.add()) }
        assertEquals(0, b.bucketAt(0))
        assertEquals(0, b.phaseOf(0))
    }

    @Test
    fun longIntervalsUseCappedBucketsSpreadOverTheInterval() {
        val b = BucketBalancer(BucketBalancer.MAX_INTERVAL)
        assertEquals(BucketBalancer.DEFAULT_MAX_BUCKETS, b.buckets)
        // Every bucket fires at exactly one phase, phases are increasing and inside the interval.
        var fired = 0
        var last = -1
        for (phase in 0 until b.interval) {
            val bucket = b.bucketAt(phase)
            if (bucket >= 0) {
                assertEquals(phase, b.phaseOf(bucket))
                assertTrue(bucket > last)
                last = bucket
                fired++
            }
        }
        assertEquals(b.buckets, fired)
        // Even spread: gaps between firing phases differ by at most one tick.
        val gaps = (1 until b.buckets).map { b.phaseOf(it) - b.phaseOf(it - 1) }
        assertTrue(gaps.max() - gaps.min() <= 1, "gaps $gaps")
    }

    @Test
    fun phaseMappingIsExactForEveryIntervalUpToTheCapAndAFewAbove() {
        for (interval in (1..1100) + listOf(1500, 2047, 4096, 9999, 72_000)) {
            val b = BucketBalancer(interval)
            val seen = IntArray(b.buckets)
            for (phase in 0 until interval) {
                val bucket = b.bucketAt(phase)
                if (bucket >= 0) seen[bucket]++
            }
            assertTrue(seen.all { it == 1 }, "interval $interval: each bucket fires once")
        }
    }

    @Test
    fun rejectsBadIntervals() {
        assertThrows(IllegalArgumentException::class.java) { BucketBalancer(0) }
        assertThrows(IllegalArgumentException::class.java) { BucketBalancer(-5) }
    }
}
