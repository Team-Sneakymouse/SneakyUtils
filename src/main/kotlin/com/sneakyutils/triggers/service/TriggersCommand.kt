package com.sneakyutils.triggers.service

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import java.util.Locale

/**
 * `/triggers [stats|top|profile on|off]` (permission [PERMISSION], op by default): what the trigger service holds and
 * what it costs. An admin tool, so it shows plugin names and subscription ids, never player data.
 *
 * - `stats` (default): subscriptions per kind and owner, areas and chunks indexed, players tracked and riding,
 *   all-online timers (flagged), and every always-on counter.
 * - `top`: the subscriptions with the most handler calls, and with the most handler time while timing was on, with
 *   their diagnostics labels and slow calls (1 ms or more).
 * - `profile on|off`: time every handler call with `System.nanoTime` without a SneakyDiagnostics capture. `on` clears
 *   the previous times. Leave it off in normal play: timing costs two clock reads per handler call.
 */
internal class TriggersCommand(private val running: () -> TriggerService?) {
    private val service: TriggerService
        get() = running() ?: throw IllegalStateException("SneakyUtils triggers are not running")

    fun create(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("triggers")
            .requires { it.sender.hasPermission(PERMISSION) }
            .executes { send(it) { statsLines() } }
            .then(Commands.literal("stats").executes { send(it) { statsLines() } })
            .then(Commands.literal("top").executes { send(it) { topLines(TOP) } })
            .then(
                Commands.literal("profile")
                    .then(Commands.literal("on").executes { send(it) { profile(true) } })
                    .then(Commands.literal("off").executes { send(it) { profile(false) } }),
            )
            .build()

    private fun send(context: CommandContext<CommandSourceStack>, lines: () -> List<String>): Int {
        val sender = context.source.sender
        if (running() == null) {
            sender.sendMessage(Component.text("SneakyUtils triggers are not running."))
            return 0
        }
        for (line in lines()) sender.sendMessage(Component.text(line))
        return 1
    }

    fun statsLines(): List<String> {
        val s = service.stats()
        val out = ArrayList<String>()
        val total = s.byKind.values.sum()
        out += "Triggers: $total subscriptions${if (total == 0) "" else " (" + join(s.byKind) + ")"}; " +
            "timing ${if (s.timing) "on" else "off"}${if (service.profileOn) " (profile)" else ""}"
        out += "Areas: ${s.count("area")} in ${s.areaChunks} chunk entries; players tracked ${s.trackedPlayers}, " +
            "riding ${s.mountedPlayers}"
        if (s.allOnlineTimers > 0) {
            out += "All-online player timers: ${s.allOnlineTimers} (flagged: prefer timers for the players that need them)"
        }
        for ((owner, kinds) in s.byOwner) out += "  $owner: ${join(kinds)}"
        for (w in s.warnings) out += "Warning: $w"
        out += "Counters: " + s.counters.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return out
    }

    fun topLines(n: Int): List<String> {
        val subs = service.stats().subscriptions
        val out = ArrayList<String>()
        val byCalls = subs.filter { it.calls > 0 }.sortedByDescending { it.calls }.take(n)
        out += if (byCalls.isEmpty()) "No handler calls yet." else "Top by calls:"
        for (s in byCalls) out += "  ${name(s)}  calls ${s.calls}  errors ${s.errors}"
        // Averages over the measured calls only (calls made while timing was off took unknown time).
        val byTime = service.subscriptions().filter { it.nanos > 0 }.sortedByDescending { it.nanos }.take(n)
        if (byTime.isEmpty()) {
            out += "No handler time measured (needs a SneakyDiagnostics capture or /triggers profile on)."
        } else {
            out += "Top by time:"
            for (s in byTime) {
                val ms = s.nanos / 1_000_000.0
                val avgUs = s.nanos / 1000.0 / s.timedCalls.coerceAtLeast(1)
                val label = if (s.label != null) "${s.id} [${s.label}]" else s.id
                out += "  $label  ${fmt(ms)} ms total  ${fmt(avgUs)} us/call  timed calls ${s.timedCalls}  slow ${s.slow}"
            }
        }
        return out
    }

    fun profile(on: Boolean): List<String> {
        if (on) {
            for (sub in service.subscriptions()) {
                sub.nanos = 0
                sub.slow = 0
                sub.timedCalls = 0
            }
            service.pollNanos = 0
        }
        service.profileOn = on
        service.refreshTiming()
        return listOf(if (on) "Trigger profiling on (handler times reset)." else "Trigger profiling off.")
    }

    companion object {
        const val PERMISSION = "sneakyutils.command.triggers"
        private const val TOP = 10

        /** The subscription id, with its diagnostics label when it has one. */
        private fun name(s: com.sneakyutils.triggers.SubscriptionStats) = if (s.label != null) "${s.id} [${s.label}]" else s.id

        private fun join(m: Map<String, Int>) = m.entries.joinToString(", ") { "${it.key} ${it.value}" }
        private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    }
}
