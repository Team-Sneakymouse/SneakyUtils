package com.sneakyutils.triggers.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class LongObjectMapTest {

    @Test
    fun putGetReplaceRemove() {
        val m = LongObjectMap<String>()
        assertNull(m.put(5L, "a"))
        assertEquals("a", m.put(5L, "b"))
        assertEquals("b", m[5L])
        assertEquals(1, m.size)
        assertEquals("b", m.remove(5L))
        assertNull(m.remove(5L))
        assertNull(m[5L])
        assertTrue(m.isEmpty)
    }

    @Test
    fun zeroNegativeAndExtremeKeysAreOrdinaryKeys() {
        val m = LongObjectMap<String>()
        val keys = longArrayOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L shl 32, -(1L shl 32))
        for (k in keys) m.put(k, "v$k")
        for (k in keys) assertEquals("v$k", m[k])
        assertEquals(keys.size, m.size)
        assertEquals("v0", m.remove(0L))
        assertNull(m[0L])
        for (k in keys.drop(1)) assertEquals("v$k", m[k])
    }

    @Test
    fun collidingKeysShareAProbeRunAndSurviveRemovalInAnyOrder() {
        // Find keys with the same home slot in a 16-slot table.
        val m = LongObjectMap<String>(4)
        assertEquals(8, m.capacity)
        val colliding = (0L until 100_000L).filter { (LongObjectMap.hash(it) and 7) == 3 }.take(3)
        assertEquals(3, colliding.size)
        for (k in colliding) m.put(k, "v$k")
        // Remove the first of the run: the later ones must still be found (backward shift).
        m.remove(colliding[0])
        assertNull(m[colliding[0]])
        assertEquals("v${colliding[1]}", m[colliding[1]])
        assertEquals("v${colliding[2]}", m[colliding[2]])
        m.remove(colliding[2])
        assertEquals("v${colliding[1]}", m[colliding[1]])
        assertEquals(1, m.size)
    }

    @Test
    fun runsThatWrapAroundTheTableEnd() {
        val m = LongObjectMap<String>(4)
        val atEnd = (0L until 100_000L).filter { (LongObjectMap.hash(it) and 7) == 7 }.take(3)
        for (k in atEnd) m.put(k, "v$k")
        m.remove(atEnd[1])
        assertEquals("v${atEnd[0]}", m[atEnd[0]])
        assertEquals("v${atEnd[2]}", m[atEnd[2]])
        assertNull(m[atEnd[1]])
    }

    @Test
    fun growsAndKeepsEverything() {
        val m = LongObjectMap<Long>()
        val start = m.capacity
        for (i in 0 until 10_000) m.put(i * 31L - 5000, i.toLong())
        assertTrue(m.capacity > start)
        assertTrue(m.size * 2 <= m.capacity, "at most half full")
        for (i in 0 until 10_000) assertEquals(i.toLong(), m[i * 31L - 5000])
    }

    @Test
    fun randomOperationsMatchAHashMap() {
        val rnd = Random(42)
        val m = LongObjectMap<Int>()
        val ref = HashMap<Long, Int>()
        repeat(200_000) {
            // A small key space forces many collisions, removals and reinsertions.
            val k = (rnd.nextInt(2_000) - 1_000).toLong() shl (rnd.nextInt(3) * 16)
            when (rnd.nextInt(3)) {
                0, 1 -> assertEquals(ref.put(k, it), m.put(k, it))
                else -> assertEquals(ref.remove(k), m.remove(k))
            }
        }
        assertEquals(ref.size, m.size)
        for ((k, v) in ref) assertEquals(v, m[k])
        var seen = 0
        m.forEachEntry { k, v ->
            assertEquals(ref[k], v)
            seen++
        }
        assertEquals(ref.size, seen)
    }

    @Test
    fun clearEmptiesTheMap() {
        val m = LongObjectMap<String>()
        for (i in 0 until 100) m.put(i.toLong(), "x")
        m.clear()
        assertTrue(m.isEmpty)
        assertFalse(m.containsKey(3L))
        m.put(3L, "y")
        assertEquals("y", m[3L])
    }
}
