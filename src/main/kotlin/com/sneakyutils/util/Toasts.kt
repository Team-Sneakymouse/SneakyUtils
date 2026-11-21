package com.sneakyutils.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sneakyutils.SneakyUtils
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.entity.Player
import java.util.UUID

/**
 * A toast in the corner of one player's screen, as MagicSpells' `toast` effect showed. The game only shows a toast
 * for an advancement being earned, so each distinct title and icon gets a hidden advancement of its own
 * (`sneakyutils:toast_<id>`). It is made the first time it's needed and then reused. The player is given it and it is
 * taken back a tick later.
 *
 * Bukkit saves runtime advancements in the world's `bukkit` datapack and resends advancements to everyone online when
 * one is added. Keep the set of distinct titles small: use fixed text or a few variants, not free text such as player
 * names. Server thread only.
 */
object Toasts {

    private const val CRITERION = "shown"
    private val made = HashMap<String, Advancement>()
    private var warned = false

    /** Shows [title] (legacy `&` codes or MiniMessage, as [TextUtility]) with an icon of [icon], using [itemModel] when given. */
    @JvmStatic
    @JvmOverloads
    fun show(player: Player, title: String, icon: Material, itemModel: NamespacedKey? = null) {
        if (title.isBlank()) return
        show(player, TextUtility.convertToComponent(title), icon, itemModel)
    }

    /** Shows [title] with an icon of [icon], using [itemModel] when given. */
    @JvmStatic
    @JvmOverloads
    fun show(player: Player, title: Component, icon: Material, itemModel: NamespacedKey? = null) {
        val advancement = advancement(json(title, icon, itemModel)) ?: return
        val progress = player.getAdvancementProgress(advancement)
        if (progress.isDone) progress.revokeCriteria(CRITERION)
        progress.awardCriteria(CRITERION)
        Bukkit.getScheduler().runTaskLater(SneakyUtils.instance, Runnable {
            if (player.isOnline) player.getAdvancementProgress(advancement).revokeCriteria(CRITERION)
        }, 1L)
    }

    @Suppress("DEPRECATION") // UnsafeValues#loadAdvancement: the only way to add an advancement at runtime
    private fun advancement(json: String): Advancement? {
        made[json]?.let { return it }
        val id = UUID.nameUUIDFromBytes(json.toByteArray()).toString().replace("-", "")
        val key = NamespacedKey(SneakyUtils.IDENTIFIER, "toast_$id")
        val advancement = Bukkit.getAdvancement(key) ?: try {
            Bukkit.getUnsafe().loadAdvancement(key, json)
        } catch (e: RuntimeException) {
            if (!warned) {
                warned = true
                SneakyUtils.instance.logger.warning("Toasts: couldn't make advancement $key: $e")
            }
            null
        } ?: return null
        made[json] = advancement
        return advancement
    }

    internal fun json(title: Component, icon: Material, itemModel: NamespacedKey?): String {
        val iconJson = JsonObject()
        iconJson.addProperty("id", icon.key.toString())
        if (itemModel != null) {
            val components = JsonObject()
            components.addProperty("minecraft:item_model", itemModel.toString())
            iconJson.add("components", components)
        }
        val display = JsonObject()
        display.add("icon", iconJson)
        display.add("title", JsonParser.parseString(GsonComponentSerializer.gson().serialize(title)))
        display.addProperty("description", "")
        display.addProperty("frame", "task")
        display.addProperty("show_toast", true)
        display.addProperty("announce_to_chat", false)
        display.addProperty("hidden", true)
        val criterion = JsonObject()
        criterion.addProperty("trigger", "minecraft:impossible")
        val criteria = JsonObject()
        criteria.add(CRITERION, criterion)
        val root = JsonObject()
        root.add("display", display)
        root.add("criteria", criteria)
        return root.toString()
    }
}
