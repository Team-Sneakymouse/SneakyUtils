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
        assertTrue(fake.stats().warnings.isEmpty())
    }
}
