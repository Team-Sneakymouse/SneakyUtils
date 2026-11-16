package com.sneakyutils.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class ExperienceUtilsTest {

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
    fun theLastSupportedLevelHasACost() {
        val p = server.addPlayer()
        p.level = 499
        p.exp = 0f
        ExperienceUtils.changeExp(p, 10)
        assertEquals(499, p.level)
        assertTrue(p.exp > 0f && p.exp < 1f, "progress ${p.exp}")
    }

    @Test
    fun levelsPastTheTableDoNotThrow() {
        val p = server.addPlayer()
        p.level = 600
        assertEquals(ExperienceUtils.getCurrentExp(p), ExperienceUtils.getCurrentExp(p.also { it.level = 500 }))
        p.level = 600
        ExperienceUtils.changeExp(p, 5)
        assertEquals(600, p.level)
        ExperienceUtils.changeExp(p, -100)
        assertEquals(600, p.level)
    }
}
