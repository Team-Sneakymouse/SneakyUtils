package com.sneakyutils

import com.sneakyutils.integration.Probe
import com.sneakyutils.triggers.Triggers
import com.sneakyutils.triggers.service.TriggersCommand
import com.sneakyutils.triggers.service.TriggerService
import com.sneakyutils.util.AlteredBlockListener
import com.sneakyutils.util.AlteredBlockManager
import com.sneakyutils.util.PlayerCheckCache
import com.sneakyutils.util.PlayerConcealment
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

/**
 * Public functions in com.sneakyutils.* are a published API every Sneaky plugin compiles against: add overloads,
 * don't rename, remove or change semantics (search repository source trees for usages first).
 */
open class SneakyUtils : JavaPlugin() {

    /** SneakyDiagnostics measurements (optional; does nothing without SneakyDiagnostics). */
    private val probe = Probe(this, "sneakyutils-ticks-2")

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

        private var playerConcealment: PlayerConcealment? = null

        /** Concealed players ([com.sneakyutils.util.PlayerUtil.conceal]); null while SneakyUtils isn't enabled. */
        @JvmStatic
        fun getPlayerConcealment(): PlayerConcealment? = playerConcealment

        private var triggers: TriggerService? = null

        /** The shared trigger service ([Triggers], `docs/triggers.md`). Throws while SneakyUtils isn't enabled. */
        @JvmStatic
        fun getTriggers(): Triggers =
            triggers ?: error("SneakyUtils is not enabled; Triggers is unavailable")

        /** The shared trigger service, or null while SneakyUtils isn't enabled. */
        @JvmStatic
        fun triggersOrNull(): Triggers? = triggers

        fun log(message: String) {
            instance.logger.info(message)
        }
    }

    override fun onLoad() {
        instance = this
        // Registered once, here: Paper refuses lifecycle handlers after the first enable. The command reads the
        // running service on each use and answers "not running" while SneakyUtils is off.
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(TriggersCommand { triggers }.create(), "Trigger service stats and profiling")
        }
    }

    /** Set by the first enable. Paper closes a plugin's jar when it is disabled, so this instance can't run again. */
    private var enabledOnce = false

    override fun onEnable() {
        if (enabledOnce) {
            // Enabled again after a disable (plugman): classes not loaded yet can't be read from the closed jar, and
            // Paper puts the closed class loader back in the lookup chain other plugins use, so the next /ms reload
            // fails for everyone. Stay off instead; disabling again takes the class loader back out.
            logger.severe(
                "SneakyUtils can't be enabled again after a disable (Paper closed its jar); it stays off. " +
                    "Restart the server to turn it back on.",
            )
            server.pluginManager.disablePlugin(this)
            return
        }
        enabledOnce = true
        alteredBlockManager = AlteredBlockManager(this)
        server.pluginManager.registerEvents(AlteredBlockListener(), this)
        playerConcealment = PlayerConcealment(this).also { it.start() }
        saveDefaultConfig()
        PlayerCheckCache.active = PlayerCheckCache(this).also {
            it.configure(config)
            it.start()
        }
        triggers = TriggerService(this, probe).also { it.start() }
        probe.hook()
        logger.info("SneakyUtils enabled.")
    }

    override fun onDisable() {
        probe.unhook()
        triggers?.shutdown()
        triggers = null
        PlayerCheckCache.active?.shutdown()
        PlayerCheckCache.active = null
        playerConcealment?.shutdown()
        playerConcealment = null
        alteredBlockManager?.restoreAll()
        alteredBlockManager = null
        logger.info("SneakyUtils disabled.")
    }
}
