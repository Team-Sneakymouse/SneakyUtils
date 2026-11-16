package com.sneakyutils.util

import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.time.Duration

class BlockUtilsAirTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun anAirColumnOverTheVoidStopsAtTheBottomOfTheWorld() {
        val world = WorldMock(Material.AIR, 0).also { server.addWorld(it) }
        for (x in -1..1) for (z in -1..1) world.getBlockAt(x, world.minHeight, z).type = Material.AIR  // no bedrock
        val found: org.bukkit.block.Block? = assertTimeoutPreemptively(Duration.ofSeconds(5), org.junit.jupiter.api.function.ThrowingSupplier {
            BlockUtils.findNearestAirRecursive(Location(world, 0.5, 10.5, 0.5), 1)
        })
        assertEquals(world.minHeight, found!!.y)
    }
}
