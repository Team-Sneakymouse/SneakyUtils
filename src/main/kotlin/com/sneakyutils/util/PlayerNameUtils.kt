package com.sneakyutils.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player

object PlayerNameUtils {

    private val nameFormatting = Regex(
        "<[^>]*>|[&§][xX](?:[&§][0-9a-fA-F]){6}|[&§]#[0-9a-fA-F]{6}|[&§][0-9a-fk-orA-FK-OR]"
    )

    /**
     * Returns SCM's raw character name with angle tags and legacy/hex codes removed, or [Player.getName] when SCM is disabled,
     * absent, incompatible, or has no nonblank name for this player.
     *
     * Call on the server thread: SCM's active-character map is not thread-safe.
     * Uses the enabled manager's own classloader; no SCM/PAPI dependency or reverse
     * load-order declaration is needed. Names are not cached, trimmed, or parsed as MiniMessage.
     */
    @JvmStatic
    fun getCharacterName(player: Player): String {
        val manager = Bukkit.getPluginManager().getPlugin("SneakyCharacterManager")
        if (manager == null || !manager.isEnabled) return player.name
        return try {
            val characterType = manager.javaClass.classLoader.loadClass(
                "net.sneakycharactermanager.paper.handlers.character.Character"
            )
            val character = characterType.getMethod("get", Player::class.java).invoke(null, player)
                ?: return player.name
            val name = characterType.getMethod("getName").invoke(character) as? String
            name?.replace(nameFormatting, "")?.takeUnless { it.isBlank() } ?: player.name
        } catch (_: ReflectiveOperationException) {
            player.name
        } catch (_: LinkageError) {
            player.name
        }
    }

    @JvmStatic
    fun getPlayerExact(playerName: String): Player? {
        // TODO come up with a non depreciated system
        return Bukkit.getPlayerExact(playerName)
    }

    @JvmStatic
    fun getPlayer(playername: String): Player? {
        // TODO come up with a non depreciated system
        return Bukkit.getPlayer(playername)
    }
}
