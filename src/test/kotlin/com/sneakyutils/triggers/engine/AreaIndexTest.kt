package com.sneakyutils.triggers.engine

import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AreaIndexTest {
    private val world = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun reg(x1: Int, z1: Int, x2: Int, z2: Int, opts: AreaOptions = AreaOptions.DEFAULT, w: UUID = world) =
        AreaReg(Area.box(w, x1, 0, z1, x2, 10, z2), opts)

    private fun at(index: AreaIndex, x: Int, z: Int, w: UUID = world) = index.world(w)?.areasAtBlock(x, z)?.toList() ?: emptyList()

    @Test
    fun areaIsListedInEveryChunkItTouchesAcrossBorders() {
        val index = AreaIndex()
        val r = reg(14, 14, 17, 17) // chunks (0,0), (1,0), (0,1), (1,1)
        index.add(r)
        assertEquals(4, index.chunkEntries)
        for ((x, z) in listOf(14 to 14, 17 to 14, 14 to 17, 17 to 17, 0 to 0, 31 to 31)) {
            assertEquals(listOf(r), at(index, x, z), "block $x,$z")
        }
        assertEquals(emptyList<AreaReg>(), at(index, 32, 0))
        assertEquals(emptyList<AreaReg>(), at(index, -1, 0))
    }

    @Test
    fun negativeCoordinatesUseFloorChunks() {
        val index = AreaIndex()
        val r = reg(-17, -1, -16, 0) // x chunks -2 and -1, z chunks -1 and 0
        index.add(r)
        assertEquals(4, index.chunkEntries)
        assertEquals(listOf(r), at(index, -32, -16))
        assertEquals(listOf(r), at(index, -1, 15))
        assertEquals(emptyList<AreaReg>(), at(index, -33, 0))
        assertEquals(emptyList<AreaReg>(), at(index, 0, 0))
    }

    @Test
    fun beyondThirtyMillion() {
        val index = AreaIndex()
        val r = reg(30_000_000, -30_000_100, 30_000_040, -30_000_060)
        index.add(r)
        assertEquals(listOf(r), at(index, 30_000_020, -30_000_080))
        assertEquals(emptyList<AreaReg>(), at(index, 30_000_020 - 64, -30_000_080))
    }

    @Test
    fun overlappingAreasShareChunksAndRemovalIsCopyOnWrite() {
        val index = AreaIndex()
        val a = reg(0, 0, 5, 5)
        val b = reg(3, 3, 9, 9)
        index.add(a)
        val before = index.world(world)!!.areasAtBlock(0, 0)!!
        index.add(b)
        val both = index.world(world)!!.areasAtBlock(0, 0)!!
        assertNotSame(before, both, "adding replaces the array")
        assertEquals(listOf(a), before.toList(), "a snapshot taken before is unchanged")
        assertEquals(listOf(a, b), both.toList())
        index.remove(a)
        assertEquals(listOf(a, b), both.toList(), "snapshot unchanged by removal")
        assertEquals(listOf(b), at(index, 0, 0))
        assertFalse(a.active)
        index.remove(b)
        assertNull(index.world(world), "the world's index goes with its last area")
        assertEquals(0, index.worldCount)
        assertEquals(0, index.chunkEntries)
        assertEquals(0, index.areaCount)
    }

    @Test
    fun versionChangesOnEveryAddAndRemove() {
        val index = AreaIndex()
        val v0 = index.version
        val a = reg(0, 0, 1, 1)
        index.add(a)
        val v1 = index.version
        assertTrue(v1 != v0)
        index.remove(a)
        assertTrue(index.version != v1)
        val v2 = index.version
        index.remove(a) // already removed: no change
        assertEquals(v2, index.version)
    }

    @Test
    fun addingTwiceIsABug() {
        val index = AreaIndex()
        val a = reg(0, 0, 1, 1)
        index.add(a)
        assertThrows(IllegalStateException::class.java) { index.add(a) }
    }

    @Test
    fun worldsAreSeparate() {
        val index = AreaIndex()
        val a = reg(0, 0, 1, 1)
        val b = reg(0, 0, 1, 1, w = other)
        index.add(a)
        index.add(b)
        assertEquals(listOf(a), at(index, 0, 0))
        assertEquals(listOf(b), at(index, 0, 0, other))
    }

    @Test
    fun largestAllowedAreaIsIndexedAndBiggerIsRejected() {
        val index = AreaIndex()
        val r = reg(0, 0, 64 * 16 - 1, 64 * 16 - 1)
        index.add(r)
        assertEquals(64 * 64, index.chunkEntries)
        val e = assertThrows(IllegalArgumentException::class.java) { reg(0, 0, 64 * 16, 10) }
        assertTrue(e.message!!.contains("65 x 1"))
        // A 2-block box that straddles a chunk border at the limit still spans only 2 chunks.
        index.add(reg(-1, -1, 0, 0))
    }

    @Test
    fun passThroughBoundsTrackAddAndRemove() {
        val index = AreaIndex()
        val pt = AreaOptions.builder().passThrough(true).build()
        val a = reg(0, 0, 10, 10, pt)
        val b = reg(100, -50, 110, -40, pt)
        index.add(a)
        index.add(b)
        index.add(reg(500, 500, 501, 501)) // not pass-through: doesn't widen the bounds
        val w = index.world(world)!!
        assertEquals(2, w.passThroughCount)
        assertEquals(listOf(0, -50, 110, 10), listOf(w.ptMinX, w.ptMinZ, w.ptMaxX, w.ptMaxZ))
        index.remove(b)
        assertEquals(1, w.passThroughCount)
        assertEquals(listOf(0, 0, 10, 10), listOf(w.ptMinX, w.ptMinZ, w.ptMaxX, w.ptMaxZ))
        index.remove(a)
        assertEquals(0, w.passThroughCount)
    }

    @Test
    fun worldIndexIsDroppedWithItsLastAreaAndRebuiltOnTheNext() {
        val index = AreaIndex()
        val a = reg(0, 0, 1, 1, w = other)
        index.add(a)
        val first = index.world(other)!!
        val v = index.version
        index.remove(a)
        assertNull(index.world(other))
        assertTrue(index.version != v, "player caches see a new version and re-read (getting null)")
        val b = reg(0, 0, 1, 1, w = other)
        index.add(b)
        assertNotSame(first, index.world(other))
        assertEquals(listOf(b), at(index, 0, 0, other))
        assertEquals(listOf(b), index.regsInWorld(other))
        assertEquals(emptyList<AreaReg>(), index.regsInWorld(world))
    }

    @Test
    fun allRegsListsEachAreaOnce() {
        val index = AreaIndex()
        val a = reg(0, 0, 100, 100)
        val b = reg(5, 5, 6, 6)
        index.add(a)
        index.add(b)
        assertEquals(setOf(a, b), index.allRegs().toSet())
        assertEquals(2, index.allRegs().size)
        index.clear()
        assertFalse(a.active)
        assertSame(null, index.world(world))
    }
}
