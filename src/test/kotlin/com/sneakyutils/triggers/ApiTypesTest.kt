package com.sneakyutils.triggers

import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ApiTypesTest {
    private val world = UUID.randomUUID()

    @Test
    fun blockKeyPacksLikeBlockPosAndRoundTripsTheWholeRange() {
        val xs = intArrayOf(0, 1, -1, 15, 16, -16, -17, 29_999_999, -30_000_000, BlockKey.MIN_XZ, BlockKey.MAX_XZ)
        val ys = intArrayOf(0, -1, -64, 319, -2032, 2031, BlockKey.MIN_Y, BlockKey.MAX_Y)
        for (x in xs) for (z in xs) for (y in ys) {
            val p = BlockKey.pack(x, y, z)
            assertEquals(x, BlockKey.unpackX(p), "x $x")
            assertEquals(y, BlockKey.unpackY(p), "y $y")
            assertEquals(z, BlockKey.unpackZ(p), "z $z")
        }
        // Same layout as Minecraft's BlockPos.asLong: x << 38 | z << 12 | y.
        assertEquals((5L shl 38) or (7L shl 12) or 64L, BlockKey.pack(5, 64, 7))
        assertEquals(-1L, BlockKey.pack(-1, -1, -1))
    }

    @Test
    fun neighbouringBlocksNeverCollide() {
        val seen = HashSet<Long>()
        for (x in -2..2) for (y in -65..-63) for (z in -2..2) assertTrue(seen.add(BlockKey.pack(x, y, z)))
        for (y in 318..320) assertTrue(seen.add(BlockKey.pack(0, y, 0)))
    }

    @Test
    fun outOfRangeKeysAreValuesButNotPackable() {
        val high = BlockKey(world, 0, 3000, 0)
        assertFalse(high.isPackable)
        assertThrows(IllegalStateException::class.java) { high.asLong() }
        assertFalse(BlockKey.isPackable(BlockKey.MAX_XZ + 1, 0, 0))
        assertFalse(BlockKey.isPackable(0, BlockKey.MIN_Y - 1, 0))
        assertTrue(BlockKey(world, -30_000_000, -64, 30_000_000).isPackable)
    }

    @Test
    fun blockKeyEquality() {
        assertEquals(BlockKey(world, 1, 2, 3), BlockKey(world, 1, 2, 3))
        assertEquals(BlockKey(world, 1, 2, 3).hashCode(), BlockKey(world, 1, 2, 3).hashCode())
        assertNotEquals(BlockKey(world, 1, 2, 3), BlockKey(UUID.randomUUID(), 1, 2, 3))
        assertNotEquals(BlockKey(world, 1, 2, 3), BlockKey(world, 1, 3, 2))
    }

    @Test
    fun boxNormalizesCornersAndContainsIsInclusive() {
        val a = Area.box(world, 10, 70, -5, 5, 60, -10)
        assertEquals(listOf(5, 60, -10, 10, 70, -5), listOf(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ))
        assertTrue(a.contains(5.0, 60.0, -10.0))
        assertTrue(a.contains(10.999, 70.999, -5.001))
        assertFalse(a.contains(11.0, 65.0, -7.0))
        assertTrue(a.contains(7.0, 65.0, -4.9999), "z -4.9999 is block -5, inside")
        assertFalse(a.contains(7.0, 65.0, -4.0), "z -4.0 is block -4, outside")
    }

    @Test
    fun customAreaUsesItsPredicateInsideItsBox() {
        // A disc of radius 5 around (0.5, 0.5) inside the box -5..5.
        val disc = Area.custom(world, -5, 0, -5, 5, 10, 5) { x, _, z -> (x - 0.5) * (x - 0.5) + (z - 0.5) * (z - 0.5) <= 25.0 }
        assertTrue(disc.isCustom)
        assertTrue(disc.contains(0.5, 5.0, 0.5))
        assertFalse(disc.contains(5.4, 5.0, 5.4), "corner of the box is outside the disc")
        assertFalse(disc.contains(20.0, 5.0, 0.5), "outside the box: predicate not even asked")
    }

    @Test
    fun customFromBoundingBoxCoversEveryOverlappedBlock() {
        // WorldGuard-style bounds of blocks 0..10 are 0.0..11.0.
        val a = Area.custom(org.mockito.Mockito.mock(org.bukkit.World::class.java).also {
            org.mockito.Mockito.`when`(it.uid).thenReturn(world)
        }, org.bukkit.util.BoundingBox(0.0, 64.0, -3.5, 11.0, 70.0, 2.0)) { _, _, _ -> true }
        assertEquals(listOf(0, 64, -4, 10, 69, 1), listOf(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ))
    }

    @Test
    fun areaSpanLimit() {
        Area.box(world, 0, 0, 0, 1023, 0, 1023)
        assertThrows(IllegalArgumentException::class.java) { Area.box(world, 0, 0, 0, 1024, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Area.box(world, -1, 0, 0, 1023, 0, 0) }
    }

    @Test
    fun sphereContainsItsSurfaceAndItsBoxCoversIt() {
        val ball = Area.sphere(world, 0.0, 64.0, 0.0, 2.0)
        assertTrue(ball.isCustom)
        assertEquals(listOf(-2, 62, -2, 2, 66, 2), listOf(ball.minX, ball.minY, ball.minZ, ball.maxX, ball.maxY, ball.maxZ),
            "an integer surface (x = 2.0) is in block 2, so the box reaches it")
        for ((x, y, z) in listOf(Triple(2.0, 64.0, 0.0), Triple(-2.0, 64.0, 0.0), Triple(0.0, 66.0, 0.0),
            Triple(0.0, 62.0, 0.0), Triple(0.0, 64.0, 2.0), Triple(0.0, 64.0, -2.0))) {
            assertTrue(ball.contains(x, y, z), "surface point $x $y $z")
        }
        assertFalse(ball.contains(2.0001, 64.0, 0.0))
        assertFalse(ball.contains(1.5, 65.5, 0.0), "in the box, outside the ball")
        assertTrue(ball.contains(1.0, 65.0, 1.0))

        val off = Area.sphere(world, 0.5, 64.5, 0.5, 3.0)
        assertEquals(listOf(-3, 61, -3, 3, 67, 3), listOf(off.minX, off.minY, off.minZ, off.maxX, off.maxY, off.maxZ))
        assertTrue(off.contains(3.5, 64.5, 0.5))
        assertFalse(off.contains(3.5, 64.5, 1.0))
    }

    @Test
    fun cylinderStandsOnItsCentreAndIncludesBaseTopAndSide() {
        val can = Area.cylinder(world, 10.5, 64.0, 10.5, 3.0, 2.5)
        assertEquals(listOf(7, 64, 7, 13, 66, 13), listOf(can.minX, can.minY, can.minZ, can.maxX, can.maxY, can.maxZ))
        assertTrue(can.contains(10.5, 64.0, 10.5), "base")
        assertTrue(can.contains(10.5, 66.5, 10.5), "top")
        assertFalse(can.contains(10.5, 63.999, 10.5), "under the base")
        assertFalse(can.contains(10.5, 66.501, 10.5), "over the top")
        assertTrue(can.contains(13.5, 65.0, 10.5), "side")
        assertFalse(can.contains(13.5, 65.0, 11.0), "just outside the side")
        assertTrue(Area.cylinder(world, 0.0, 64.0, 0.0, 1.0, 0.0).contains(0.5, 64.0, 0.5), "a flat disc: height 0")
    }

    @Test
    fun translateMovesTheBoxAndTheShapeWithoutNesting() {
        val ball = Area.sphere(world, 0.5, 64.0, 0.5, 2.0)
        var moved = ball
        repeat(1000) { moved = moved.translate(1, 0, -1) }
        assertEquals(listOf(ball.minX + 1000, ball.minZ - 1000), listOf(moved.minX, moved.minZ))
        assertTrue(moved.contains(1002.5, 64.0, -999.5), "the surface moved with it")
        assertFalse(moved.contains(2.5, 64.0, 0.5))
        assertEquals(world, moved.worldId)
        assertTrue(ball.contains(2.5, 64.0, 0.5), "the original is unchanged")

        val half = Area.custom(world, 0, 0, 0, 9, 9, 9) { x, _, _ -> x < 5.0 }
        val shifted = half.translate(10, 0, 0)
        assertTrue(shifted.contains(14.9, 1.0, 1.0))
        assertFalse(shifted.contains(15.1, 1.0, 1.0))
        val boxed = Area.box(world, 0, 0, 0, 1, 1, 1).translate(0, 5, 0)
        assertFalse(boxed.isCustom)
        assertEquals(listOf(5, 6), listOf(boxed.minY, boxed.maxY))
    }

    @Test
    fun translateRefusesToLeaveTheIntRangeAndKeepsFarShapesExact() {
        // Review finding 5: an overflowing translate wrapped to inverted bounds.
        val small = Area.box(world, 0, 0, 0, 1, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { small.translate(Int.MAX_VALUE, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Area.box(world, 0, -5, 0, 1, -1, 1).translate(0, Int.MIN_VALUE, 0) }
        val far = small.translate(2_000_000_000, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { far.translate(2_000_000_000, 0, 0) }
        val ball = Area.sphere(world, 0.5, 0.5, 0.5, 1.0)
        val away = ball.translate(2_000_000_000, 0, 0).translate(-2_000_000_000, 0, 0).translate(-2_000_000_000, 0, 0)
        assertTrue(away.contains(-1_999_999_999.5, 0.5, 0.5), "a shape moved far and back stays exact")
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, 3.0e9, 0.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.cylinder(world, 0.0, 2.1e9, 0.0, 1.0, 1.0e8) }
    }

    @Test
    fun shapeRangeChecksLookAtEachBoundOnly() {
        // Fourth review, finding 3: a tall cylinder far below zero whose every bound fits was refused.
        val tall = Area.cylinder(world, 0.0, -1_500_000_000.0, 0.0, 1.0, 1_500_000_000.0)
        assertEquals(listOf(-1, -1_500_000_000, -1, 1, 0, 1), listOf(tall.minX, tall.minY, tall.minZ, tall.maxX, tall.maxY, tall.maxZ))
        assertThrows(IllegalArgumentException::class.java) { Area.cylinder(world, 0.0, 1_500_000_000.0, 0.0, 1.0, 1_500_000_000.0) }
        Area.sphere(world, 0.0, 2_000_000_000.0, 0.0, 1.0)
        // Fifth review, finding 4: bounds whose block is still an int are fine, up to the limits themselves.
        val top = Area.cylinder(world, 0.0, 2_147_483_647.0, 0.0, 1.0, 0.0)
        assertEquals(listOf(Int.MAX_VALUE, Int.MAX_VALUE), listOf(top.minY, top.maxY))
        assertEquals(Int.MAX_VALUE, Area.cylinder(world, 2_147_483_646.5, 0.0, 0.0, 1.0, 1.0).maxX)
        assertEquals(Int.MIN_VALUE, Area.sphere(world, 0.0, -2_147_483_647.0, 0.0, 1.0).minY)
        assertThrows(IllegalArgumentException::class.java) { Area.cylinder(world, 2_147_483_647.0, 0.0, 0.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.cylinder(world, 0.0, 2_147_483_647.0, 0.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, 0.0, -2_147_483_647.5, 0.0, 1.0) }
    }

    @Test
    fun aTranslatedSphereOrCylinderIsTheSameShapeAtItsMovedCentre() {
        // Ninth review, finding 1: testing by subtracting the offset rejected points on the moved shape itself.
        val disc = Area.cylinder(world, 0.0, 0.1, 0.0, 1.0, 0.0).translate(0, 1, 0)
        assertTrue(disc.contains(0.0, 0.1 + 1.0, 0.0), "its own centre, at the moved base")
        assertTrue(disc.contains(0.0, 1.1, 0.0))
        assertEquals(listOf(1, 1), listOf(disc.minY, disc.maxY))

        val ball = Area.sphere(world, 0.1, 0.7, 0.3, 0.2).translate(3, -5, 7)
        assertTrue(ball.contains(0.1 + 3, 0.7 - 5, 0.3 + 7), "its centre")
        val fresh = Area.sphere(world, 0.1 + 3, 0.7 - 5, 0.3 + 7, 0.2)
        for (dx in listOf(-0.2, -0.19999, 0.0, 0.1, 0.2, 0.20001)) {
            for (dy in listOf(-0.2, 0.0, 0.2)) {
                val x = 0.1 + 3 + dx
                val y = 0.7 - 5 + dy
                assertEquals(fresh.contains(x, y, 0.3 + 7), ball.contains(x, y, 0.3 + 7), "as a sphere made there: $dx $dy")
            }
        }
        assertEquals(listOf(fresh.minX, fresh.minY, fresh.minZ, fresh.maxX, fresh.maxY, fresh.maxZ),
            listOf(ball.minX, ball.minY, ball.minZ, ball.maxX, ball.maxY, ball.maxZ))

        // Round trips: back at the start it is the original shape exactly, however many moves it took.
        val can = Area.cylinder(world, 0.1, 0.1, 0.1, 0.3, 0.7)
        var moved = can
        repeat(500) { moved = moved.translate(7, -3, 11) }
        repeat(500) { moved = moved.translate(-7, 3, -11) }
        assertEquals(listOf(can.minX, can.minY, can.minZ, can.maxX, can.maxY, can.maxZ),
            listOf(moved.minX, moved.minY, moved.minZ, moved.maxX, moved.maxY, moved.maxZ))
        for ((x, y, z) in listOf(Triple(0.1, 0.1, 0.1), Triple(0.1, 0.1 + 0.7, 0.1), Triple(0.1 + 0.25, 0.5, 0.1))) {
            assertEquals(can.contains(x, y, z), moved.contains(x, y, z), "$x $y $z")
            assertTrue(moved.contains(x, y, z), "$x $y $z is on the cylinder")
        }
        assertEquals(can.contains(0.1 + 0.3, 0.5, 0.1), moved.contains(0.1 + 0.3, 0.5, 0.1), "the side, whichever way it rounds")
        assertFalse(moved.contains(0.1, 0.1 + 0.7 + 1e-9, 0.1))
        assertThrows(IllegalArgumentException::class.java) { can.translate(Int.MAX_VALUE, 0, 0).translate(Int.MAX_VALUE, 0, 0) }
        assertTrue(Area.custom(world, 0, 0, 0, 9, 9, 9) { x, _, _ -> x < 5.0 }.translate(10, 0, 0).contains(14.9, 1.0, 1.0),
            "a custom predicate still gets the position moved back")
    }

    @Test
    fun shapesRejectBadArgumentsAndTheSpanLimit() {
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, 0.0, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, 0.0, 0.0, 0.0, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.cylinder(world, 0.0, 0.0, 0.0, 1.0, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { Area.sphere(world, 0.0, 64.0, 0.0, 600.0) }
        Area.sphere(world, 8.0, 64.0, 8.0, 500.0)
    }

    @Test
    fun occupancyOptionDefaultsMatchConradsDecisions() {
        val o = OccupancyOptions.DEFAULT
        assertEquals(RiderPolicy.ALLOW, o.riders, "riders count")
        assertFalse(o.includeSpectators, "spectators don't")
        assertTrue(o.countDead, "the dead stay until they respawn elsewhere")
        assertEquals(null, o.label)
        val b = OccupancyOptions.builder().countDead(false)
        val first = b.build()
        b.countDead(true)
        assertFalse(first.countDead)
        val copy = first.toBuilder().includeSpectators(true).label("x:y").build()
        assertTrue(copy.includeSpectators)
        assertFalse(copy.countDead)
        assertThrows(IllegalArgumentException::class.java) { OccupancyOptions.builder().label("Bad Label") }
    }

    @Test
    fun optionDefaultsMatchTheSpec() {
        val a = AreaOptions.DEFAULT
        assertEquals(RiderPolicy.SKIP, a.riders)
        assertFalse(a.includeSpectators)
        assertFalse(a.passThrough)
        assertTrue(a.fireOnJoin)
        assertFalse(a.fireOnRegister)
        assertFalse(a.stepEvents)

        val w = WatchOptions.DEFAULT
        assertTrue(w.onFootOnly)
        assertFalse(w.keepOnQuit)
        assertTrue(w.keepOnWorldChange)
        assertFalse(w.includeTeleports)

        val c = ClickOptions.DEFAULT
        assertEquals(setOf(EquipmentSlot.HAND), c.hands)
        assertEquals(ClickSide.RIGHT, c.sides)
        assertTrue(c.ignoreCancelled)
        assertFalse(c.cancelEvent)
    }

    @Test
    fun optionsAreImmutableCopies() {
        val b = AreaOptions.builder().riders(RiderPolicy.ALLOW)
        val first = b.build()
        b.riders(RiderPolicy.ONLY)
        assertEquals(RiderPolicy.ALLOW, first.riders)
        val changed = first.toBuilder().passThrough(true).build()
        assertTrue(changed.passThrough)
        assertEquals(RiderPolicy.ALLOW, changed.riders)
        assertFalse(first.passThrough)

        assertThrows(IllegalArgumentException::class.java) { ClickOptions.builder().hands(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { ClickOptions.builder().hands(setOf(EquipmentSlot.HEAD)) }
        val both = ClickOptions.builder().hands(setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)).build()
        assertThrows(UnsupportedOperationException::class.java) { (both.hands as MutableSet).clear() }
    }

    @Test
    fun clickSideAcceptance() {
        assertTrue(ClickSide.BOTH.accepts(ClickSide.LEFT))
        assertTrue(ClickSide.BOTH.accepts(ClickSide.RIGHT))
        assertFalse(ClickSide.BOTH.accepts(ClickSide.PHYSICAL))
        assertFalse(ClickSide.RIGHT.accepts(ClickSide.LEFT))
        assertTrue(ClickSide.PHYSICAL.accepts(ClickSide.PHYSICAL))
    }

    /**
     * An implementation written against the interface as first published: only its original abstract methods. It
     * must keep compiling (new methods are default methods), and the new overloads must work on it for their
     * default options and refuse the rest loudly instead of ignoring them.
     */
    private class FirstEditionFake : Triggers {
        val calls = ArrayList<String>()
        private fun sub(): Subscription = object : Subscription {
            override val id = "fake"
            override val owner: org.bukkit.plugin.Plugin get() = throw UnsupportedOperationException()
            override val active = true
            override fun close() {}
        }

        override fun area(owner: org.bukkit.plugin.Plugin, area: Area, handler: AreaHandler, options: AreaOptions) = sub()
        override fun watchSteps(owner: org.bukkit.plugin.Plugin, player: org.bukkit.entity.Player, handler: StepHandler, options: WatchOptions) = sub()
        override fun watchBlockUnder(owner: org.bukkit.plugin.Plugin, player: org.bukkit.entity.Player, materials: Set<org.bukkit.Material>,
                                     handler: BlockUnderHandler, options: WatchOptions) = sub()
        override fun watchBlock(owner: org.bukkit.plugin.Plugin, player: org.bukkit.entity.Player, at: BlockOffset,
                                materials: Set<org.bukkit.Material>, handler: BlockWatchHandler, options: WatchOptions) = sub()
        override fun every(owner: org.bukkit.plugin.Plugin, intervalTicks: Int, task: Runnable) = sub()
        override fun everyForPlayers(owner: org.bukkit.plugin.Plugin, intervalTicks: Int, handler: PlayerTick, scope: PlayerScope): PlayerTimer {
            calls += "timer $scope"
            throw UnsupportedOperationException("not needed")
        }
        override fun onBlockClick(owner: org.bukkit.plugin.Plugin, block: BlockKey, handler: BlockClickHandler, options: ClickOptions) = sub()
        override fun onBlockType(owner: org.bukkit.plugin.Plugin, material: org.bukkit.Material, handler: BlockClickHandler, options: ClickOptions) = sub()
        override fun onBlockBreak(owner: org.bukkit.plugin.Plugin, block: BlockKey, handler: BlockBreakHandler) = sub().also { calls += "break" }
        override fun onBlockBreakType(owner: org.bukkit.plugin.Plugin, material: org.bukkit.Material, handler: BlockBreakHandler) =
            sub().also { calls += "break type" }
        override fun onEntityClick(owner: org.bukkit.plugin.Plugin, match: EntityMatch, handler: EntityClickHandler, options: ClickOptions) = sub()
        override fun loadTable(owner: org.bukkit.plugin.Plugin, file: java.io.File, actions: ActionResolver) =
            TableLoadResult(file, emptyList(), emptyList(), false)
        override fun stats() = TriggerStats(emptyMap(), emptyMap(), 0, 0, 0, 0, emptyMap(), emptyList(), false) // the original constructor
    }

    @Test
    fun methodsAddedAfterTheFirstEditionAreDefaultMethods() {
        val fake = FirstEditionFake()
        val owner = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin::class.java)
        val key = BlockKey(world, 1, 2, 3)
        fake.onBlockBreak(owner, key, { _, _, _ -> }, BreakOptions.DEFAULT)
        fake.onBlockBreakType(owner, org.bukkit.Material.STONE, { _, _, _ -> }, BreakOptions.DEFAULT)
        assertThrows(UnsupportedOperationException::class.java) { fake.everyForPlayers(owner, 5, { }, PlayerTimerOptions.DEFAULT) }
        assertEquals(listOf("break", "break type", "timer ADDED"), fake.calls, "the new overloads delegate")
        val cancelledToo = BreakOptions.builder().ignoreCancelled(false).build()
        assertThrows(UnsupportedOperationException::class.java) { fake.onBlockBreak(owner, key, { _, _, _ -> }, cancelledToo) }
        assertThrows(UnsupportedOperationException::class.java) {
            fake.everyForPlayers(owner, 5, { }, PlayerTimerOptions.builder().skipDead(false).build())
        }
        assertEquals(listOf("break", "break type", "timer ADDED"), fake.calls, "options it can't honour are refused, not ignored")
        assertThrows(UnsupportedOperationException::class.java) { fake.onBlockRemoved(owner, key, { _, _, _ -> }) }
        val area = Area.box(world, 0, 0, 0, 1, 1, 1)
        assertThrows(UnsupportedOperationException::class.java) { fake.occupancy(owner, area, object : OccupancyHandler {}) }
        assertTrue(fake.stats().warnings.isEmpty())
    }
}
