package com.sneakyutils.util

import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

class ToastsTest {

    @Test
    fun iconCarriesItemModelWhenGiven() {
        val json = JsonParser.parseString(Toasts.json(Component.text("Fire"), Material.JIGSAW, NamespacedKey("lom", "icons/dispatch_fire"))).asJsonObject
        val icon = json.getAsJsonObject("display").getAsJsonObject("icon")
        assertEquals("minecraft:jigsaw", icon.get("id").asString)
        assertEquals("lom:icons/dispatch_fire", icon.getAsJsonObject("components").get("minecraft:item_model").asString)
        assertEquals("task", json.getAsJsonObject("display").get("frame").asString)
        assertEquals("minecraft:impossible", json.getAsJsonObject("criteria").getAsJsonObject("shown").get("trigger").asString)
    }

    @Test
    fun plainIconHasNoComponents() {
        val json = JsonParser.parseString(Toasts.json(Component.text("x"), Material.STICK, null)).asJsonObject
        assertFalse(json.getAsJsonObject("display").getAsJsonObject("icon").has("components"))
    }
}
