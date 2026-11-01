package com.sneakyutils

import com.sneakyutils.util.AlteredBlockListener
import com.sneakyutils.util.AlteredBlockManager
import org.bukkit.plugin.java.JavaPlugin

/**
 * Public functions in com.sneakyutils.* are a published API every Sneaky plugin compiles against: add overloads,
 * don't rename, remove or change semantics (search repository source trees for usages first).
 */
open class SneakyUtils : JavaPlugin() {

    companion object {
        const val IDENTIFIER = "sneakyutils"
        // Frozen with the altered_block_* PDC keys (AlteredBlockManager): add new keys and migrate on load.
        const val ALTERED_BLOCK_TAG = "sneakyutils_altered_block"

        lateinit var instance: SneakyUtils
            private set

        private var alteredBlockManager: AlteredBlockManager? = null

        @JvmStatic
        fun getAlteredBlockManager(): AlteredBlockManager =
            alteredBlockManager
                ?: error("SneakyUtils is not enabled; AlteredBlockManager is unavailable")

        fun log(message: String) {
            instance.logger.info(message)
        }
    }

    override fun onLoad() {
        instance = this
    }

    override fun onEnable() {
        val expiration = com.sneakyutils.util.items.ItemExpirationListener()
        server.pluginManager.registerEvents(expiration, this)
        server.pluginManager.registerEvents(com.sneakyutils.util.items.CharacterExpirationHook(this, expiration), this)
        // Dependents register their listeners during enable before the first server tick.
        val initialPlayers = server.onlinePlayers.toList()
        if (initialPlayers.isNotEmpty()) server.scheduler.runTask(this, Runnable {
            if (isEnabled) initialPlayers.filter { it.isOnline }.forEach { expiration.joinOrLoadCharacter(it) }
        })
        server.pluginManager.registerEvents(com.sneakyutils.util.items.ItemSoulboundListener(), this)
        alteredBlockManager = AlteredBlockManager(this)
        server.pluginManager.registerEvents(AlteredBlockListener(), this)
        logger.info("SneakyUtils enabled.")
    }

    override fun onDisable() {
        com.sneakyutils.util.items.ItemExpirationScheduler.shutdown()
        alteredBlockManager?.restoreAll()
        alteredBlockManager = null
        logger.info("SneakyUtils disabled.")
    }
}
