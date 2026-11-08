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
        alteredBlockManager = AlteredBlockManager(this)
        server.pluginManager.registerEvents(AlteredBlockListener(), this)
        logger.info("SneakyUtils enabled.")
    }

    override fun onDisable() {
        alteredBlockManager?.restoreAll()
        alteredBlockManager = null
        logger.info("SneakyUtils disabled.")
    }
}
