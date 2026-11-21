package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import io.papermc.paper.advancement.AdvancementDisplay
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Advancement toasts (the popup in the top right corner) for one player, without a real advancement.
 *
 * The player's client is sent a made-up, already completed advancement and then told to forget it again, so the
 * toast shows and nothing is left in their advancement screen or saved on the server. Paper has no API for this, so
 * it uses the server's own classes by reflection (Paper runs Mojang's names). If those don't match the server, the
 * console says so once and [show] returns false from then on.
 */
object ToastUtil {

    @Volatile
    private var broken = false

    private var nms: Nms? = null

    /**
     * Shows [player] a toast with [icon] and [title]. Call on the server thread. Returns false when it couldn't be
     * sent (player offline, or the server's classes don't match).
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        player: Player,
        icon: ItemStack,
        title: Component,
        frame: AdvancementDisplay.Frame = AdvancementDisplay.Frame.TASK,
    ): Boolean {
        if (broken || !player.isOnline) return false
        val nms = nms ?: try {
            Nms().also { nms = it }
        } catch (ex: Throwable) {
            broken = true
            SneakyUtils.instance.logger.warning("Couldn't load the server classes for toasts ($ex); no toasts will show.")
            return false
        }
        return try {
            nms.send(player, icon, title, frame)
            true
        } catch (ex: Exception) {
            SneakyUtils.instance.logger.warning("Couldn't show ${player.name} a toast: $ex")
            false
        }
    }

    private class Nms {
        private val l = MethodHandles.publicLookup()
        private val adv = "net.minecraft.advancements."
        private val identifier = Class.forName("net.minecraft.resources.Identifier")
        private val component = Class.forName("net.minecraft.network.chat.Component")
        private val type = Class.forName(adv + "AdvancementType")
        private val builder = Class.forName(adv + "Advancement\$Builder")
        private val holder = Class.forName(adv + "AdvancementHolder")
        private val progress = Class.forName(adv + "AdvancementProgress")
        private val requirements = Class.forName(adv + "AdvancementRequirements")
        private val criterion = Class.forName(adv + "triggers.Criterion")
        private val impossible = Class.forName(adv + "triggers.ImpossibleTrigger")
        private val impossibleInstance = Class.forName(adv + "triggers.ImpossibleTrigger\$TriggerInstance")
        private val packet = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket")
        private val bool = Boolean::class.javaPrimitiveType!!
        private val void = Void.TYPE

        private val key: Any = l.findStatic(
            identifier, "fromNamespaceAndPath", MethodType.methodType(identifier, String::class.java, String::class.java)
        ).invokeWithArguments(SneakyUtils.IDENTIFIER, "toast")

        private val asTemplate: MethodHandle = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").let {
            l.findStatic(it, "asTemplate", MethodType.methodType(Class.forName("net.minecraft.world.item.ItemStackTemplate"), ItemStack::class.java))
        }
        private val asVanilla: MethodHandle = l.findStatic(
            Class.forName("io.papermc.paper.adventure.PaperAdventure"), "asVanilla",
            MethodType.methodType(component, Component::class.java)
        )
        private val newBuilder = l.findStatic(builder, "advancement", MethodType.methodType(builder))
        private val display = l.findVirtual(
            builder, "display",
            MethodType.methodType(builder, asTemplate.type().returnType(), component, component, identifier, type, bool, bool, bool)
        )
        private val addCriterion = l.findVirtual(builder, "addCriterion", MethodType.methodType(builder, String::class.java, criterion))
        private val build = l.findVirtual(builder, "build", MethodType.methodType(holder, identifier))
        private val newCriterion = l.findConstructor(
            criterion, MethodType.methodType(void, Class.forName(adv + "triggers.CriterionTrigger"), Class.forName(adv + "CriterionTriggerInstance"))
        )
        private val newImpossible = l.findConstructor(impossible, MethodType.methodType(void))
        private val newImpossibleInstance = l.findConstructor(impossibleInstance, MethodType.methodType(void))
        private val newProgress = l.findConstructor(progress, MethodType.methodType(void))
        private val update = l.findVirtual(progress, "update", MethodType.methodType(void, requirements))
        private val grant = l.findVirtual(progress, "grantProgress", MethodType.methodType(bool, String::class.java))
        private val newRequirements = l.findConstructor(requirements, MethodType.methodType(void, List::class.java))
        private val newPacket = l.findConstructor(
            packet, MethodType.methodType(void, bool, Collection::class.java, Set::class.java, Map::class.java, bool)
        )
        private val getHandle = l.findVirtual(
            Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer"), "getHandle",
            MethodType.methodType(Class.forName("net.minecraft.server.level.ServerPlayer"))
        )
        private val connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection")
        private val sendPacket = l.findVirtual(
            Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl"), "send",
            MethodType.methodType(void, Class.forName("net.minecraft.network.protocol.Packet"))
        )
        private val types: Map<String, Any> = type.enumConstants.associateBy { (it as Enum<*>).name }
        private val empty: Any = asVanilla.invokeWithArguments(Component.empty())

        fun send(player: Player, icon: ItemStack, title: Component, frame: AdvancementDisplay.Frame) {
            var b = newBuilder.invokeWithArguments()
            b = display.invokeWithArguments(
                b, asTemplate.invokeWithArguments(icon), asVanilla.invokeWithArguments(title), empty, null,
                types[frame.name] ?: types.getValue("TASK"), true, false, true
            )
            val trigger = newCriterion.invokeWithArguments(newImpossible.invokeWithArguments(), newImpossibleInstance.invokeWithArguments())
            b = addCriterion.invokeWithArguments(b, CRITERION, trigger)
            val advancement = build.invokeWithArguments(b, key)

            val done = newProgress.invokeWithArguments()
            update.invokeWithArguments(done, newRequirements.invokeWithArguments(listOf(listOf(CRITERION))))
            grant.invokeWithArguments(done, CRITERION)

            val conn = connection.get(getHandle.invokeWithArguments(player))
            // showAdvancements (the last argument) must be true, or the client shows no toast.
            sendPacket.invokeWithArguments(conn, newPacket.invokeWithArguments(false, setOf(advancement), emptySet<Any>(), mapOf(key to done), true))
            sendPacket.invokeWithArguments(conn, newPacket.invokeWithArguments(false, emptySet<Any>(), setOf(key), emptyMap<Any, Any>(), true))
        }
    }

    private const val CRITERION = "impossible"
}
