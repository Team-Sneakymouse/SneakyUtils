package com.sneakyutils

import com.sneakyutils.util.AlteredBlockListener
import com.sneakyutils.util.AlteredBlockManager
import org.bukkit.plugin.java.JavaPlugin

open class SneakyUtils : JavaPlugin() {

    companion object {
        const val IDENTIFIER = "sneakyutils"
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
