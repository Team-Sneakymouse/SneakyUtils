package com.sneakyutils

import com.sneakyutils.commands.ReloadCommand
import com.sneakyutils.manager.AlteredBlockListener
import com.sneakyutils.manager.AlteredBlockManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

open class SneakyUtils : JavaPlugin() {

    companion object {
        const val IDENTIFIER = "sneakyutils"
        const val ALTERED_BLOCK_TAG = "SU_ALTERED_BLOCK"

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
        saveDefaultConfig()
        alteredBlockManager = AlteredBlockManager(this)
        server.pluginManager.registerEvents(AlteredBlockListener(), this)
        reloadPlugin()
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(ReloadCommand(this).create(), "Reload the plugin configuration")
        }
        logger.info("SneakyUtils enabled.")
    }

    /** Shared configuration entry point for startup and reload. */
    fun reloadPlugin() {
        reloadConfig()
    }

    override fun onDisable() {
        alteredBlockManager = null
        logger.info("SneakyUtils disabled.")
    }
}
