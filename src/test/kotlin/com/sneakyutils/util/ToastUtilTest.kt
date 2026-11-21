package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class ToastUtilTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        MockBukkit.load(SneakyUtils::class.java)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    /** MockBukkit has no server internals: the toast is refused instead of throwing into the caller. */
    @Test
    fun withoutServerClassesShowReturnsFalse() {
        val player = server.addPlayer()
        assertFalse(ToastUtil.show(player, ItemStack(Material.PAPER), Component.text("Hi")))
        assertFalse(ToastUtil.show(player, ItemStack(Material.PAPER), Component.text("Again")))
    }
}
