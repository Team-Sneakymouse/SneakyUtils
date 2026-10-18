package com.sneakyutils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class SneakyUtilsTest {

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun loadsAndEnablesWithoutError() {
        val plugin = MockBukkit.load(SneakyUtils::class.java)
        assertNotNull(plugin)
        assertTrue(plugin.isEnabled)
        assertNotNull(SneakyUtils.instance)
        assertNotNull(SneakyUtils.getAlteredBlockManager())
        assertEquals(SneakyUtils.ALTERED_BLOCK_TAG, "sneakyutils_altered_block")
    }
}
