package com.sneakyutils.triggers.service

import com.sneakyutils.triggers.ActionResolver
import com.sneakyutils.triggers.Area
import com.sneakyutils.triggers.AreaOptions
import com.sneakyutils.triggers.BlockKey
import com.sneakyutils.triggers.BreakOptions
import com.sneakyutils.triggers.ClickOptions
import com.sneakyutils.triggers.ClickSide
import com.sneakyutils.triggers.RejectedEntry
import com.sneakyutils.triggers.RiderPolicy
import com.sneakyutils.triggers.Subscription
import com.sneakyutils.triggers.TableEntry
import com.sneakyutils.triggers.TableLoadResult
import org.bukkit.World
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Loads data tables for [com.sneakyutils.triggers.Triggers.loadTable]. The file is a YAML list of entries (or a map
 * with an `entries:` list):
 *
 * ```yaml
 * - {kind: block-click, world: world, at: [10, 64, -3], side: right, action: speakeasy-door, args: {code: 1234}}
 * - {kind: block-break, world: world, at: [11, 64, -3], action: ore-respawn}
 * - {kind: area, world: world, min: [0, 60, 0], max: [5, 70, 5], riders: skip, action: forge-bellows}
 * ```
 *
 * Keys: `kind`, `world`, `action` (required), `args` (map). block-click: `at`, `side` (right, left, both, physical),
 * `hands` (main, off, both), `cancel`, `ignore-cancelled`. block-break: `at`, `ignore-cancelled`. area: `min`, `max`, `riders` (skip,
 * allow, only), `spectators`, `pass-through`, `fire-on-join`, `fire-on-register`, `step-events`. Unknown keys reject
 * the entry, so a typo can't silently change behaviour.
 *
 * Order: the whole file is parsed and every action resolved first. If the file can't be read or parsed, nothing
 * changes and the previous load stays active. Otherwise the previous load's subscriptions close and the valid entries
 * register; each invalid entry is reported with its line.
 */
internal class TableLoader(private val service: TriggerService) {

    /** Subscriptions of each owner's loaded tables, by canonical path. */
    private val loaded = IdentityHashMap<Plugin, HashMap<String, List<Subscription>>>()

    private class Reject(val line: Int, message: String) : Exception(message)

    /** A parsed, resolved entry, ready to register. */
    private class Spec(val line: Int, val kind: String, val action: String, val register: () -> Subscription)

    /**
     * Tables being loaded right now, by owner and canonical path. Replacing a table closes and registers
     * subscriptions, which fires handlers synchronously; one of them reloading the same table would leave two sets
     * registered, so such a nested load is refused.
     */
    private val inProgress = HashSet<Pair<Plugin, String>>()

    fun load(owner: Plugin, file: File, actions: ActionResolver): TableLoadResult {
        val path = try {
            file.canonicalPath
        } catch (e: IOException) {
            file.absolutePath
        }
        val key = owner to path
        if (!inProgress.add(key)) {
            return fileError(file, 0, "${file.name} is already being loaded (a handler reloaded it during its own load); " +
                "this reload is ignored")
        }
        try {
            return loadOnce(owner, file, path, actions)
        } finally {
            inProgress.remove(key)
        }
    }

    private fun loadOnce(owner: Plugin, file: File, path: String, actions: ActionResolver): TableLoadResult {
        val text = try {
            readBounded(file) ?: return fileError(file, 0, "${file.name} is larger than ${MAX_FILE_BYTES / 1024} KiB")
        } catch (e: IOException) {
            return fileError(file, 0, "can't read ${file.name}: ${show(e.message ?: e.toString())}")
        }
        // SnakeYAML's reader copies its buffer from the start of the current token each time it reads on, so one
        // token of n characters costs about n^2 / 256 bytes (a 2 MB line: 8 GB). Lines this long are never content.
        val long = longLine(text)
        if (long > 0) return fileError(file, long, "line $long is longer than $MAX_LINE_CHARS characters")
        val root: Node? = try {
            Yaml(LoaderOptions()).compose(java.io.StringReader(text))
        } catch (e: MarkedYAMLException) {
            return fileError(file, (e.problemMark?.line ?: -1) + 1, "invalid YAML: ${show(e.problem ?: e.message ?: "", 160)}")
        } catch (e: YAMLException) {
            return fileError(file, 0, "invalid YAML: ${show(e.message ?: "", 160)}")
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            // A parser bug must not take the caller down; the previous load stays.
            return fileError(file, 0, "invalid YAML: ${show(e.toString(), 160)}")
        }
        val list: List<Node> = when (root) {
            null -> emptyList()
            is SequenceNode -> root.value
            is MappingNode -> {
                val entries = root.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == "entries" }?.valueNode
                (entries as? SequenceNode)?.value
                    ?: return fileError(file, line(root), "the table must be a list, or a map with an 'entries:' list")
            }
            else -> return fileError(file, line(root), "the table must be a list, or a map with an 'entries:' list")
        }

        val specs = ArrayList<Spec>()
        val rejected = ArrayList<RejectedEntry>()
        for (node in list) {
            try {
                specs += parse(owner, node, actions)
            } catch (r: Reject) {
                // Report the entry's own line; name the inner line when the problem is deeper in the entry.
                val entryLine = line(node)
                val reason = r.message ?: "rejected"
                rejected += RejectedEntry(entryLine, if (r.line == entryLine) reason else "line ${r.line}: $reason")
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (t: Throwable) {
                // Nothing one entry does may abort the whole load (StackOverflowError included).
                rejected += RejectedEntry(line(node), "entry failed: ${show(t.toString())}")
            }
        }

        // The file is usable: replace the previous load.
        loaded[owner]?.remove(path)?.forEach { it.close() }
        val entries = ArrayList<TableEntry>()
        for (spec in specs) {
            try {
                entries += TableEntry(spec.line, spec.kind, spec.action, spec.register())
            } catch (e: RuntimeException) {
                rejected += RejectedEntry(spec.line, show(e.message ?: e.toString(), 200))
            }
        }
        // A handler may have disabled the owner meanwhile; its subscriptions are closed then, so keep no record.
        if (owner.isEnabled) loaded.getOrPut(owner) { HashMap() }[path] = entries.map { it.subscription }
        rejected.sortBy { it.line }
        return TableLoadResult(file, entries, rejected, true)
    }

    fun ownerDisabled(owner: Plugin) {
        loaded.remove(owner)
    }

    fun clear() = loaded.clear()

    /** The file as UTF-8 text (malformed bytes replaced), or null when it is over [MAX_FILE_BYTES]. */
    private fun readBounded(file: File): String? {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) return null
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** The 1-based number of the first line longer than [MAX_LINE_CHARS], or 0. */
    private fun longLine(text: String): Int {
        var line = 1
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c != '\n' && c != '\r') continue
            if (i - start > MAX_LINE_CHARS) return line
            if (c == '\n' || i + 1 >= text.length || text[i + 1] != '\n') line++
            start = i + 1
        }
        return if (text.length - start > MAX_LINE_CHARS) line else 0
    }

    private fun fileError(file: File, line: Int, reason: String) =
        TableLoadResult(file, emptyList(), listOf(RejectedEntry(line, reason)), false)

    // ---------------------------------------------------------------- entries

    private fun parse(owner: Plugin, node: Node, actions: ActionResolver): Spec {
        val line = line(node)
        if (node !is MappingNode) throw Reject(line, "an entry must be a map")
        val fields = LinkedHashMap<String, Node>()
        for (t in node.value) {
            val key = (t.keyNode as? ScalarNode)?.value ?: throw Reject(line(t.keyNode), "keys must be plain names")
            if (fields.put(key, t.valueNode) != null) throw Reject(line(t.keyNode), "duplicate key '${show(key)}'")
        }
        val kind = string(fields, "kind", line)
        val allowed = when (kind) {
            "block-click" -> CLICK_KEYS
            "block-break" -> BREAK_KEYS
            "area" -> AREA_KEYS
            else -> throw Reject(line, "unknown kind '${show(kind)}' (block-click, block-break or area)")
        }
        for (key in fields.keys) if (key !in allowed) throw Reject(line(fields[key]!!), "unknown key '${show(key)}' for kind $kind")
        val worldName = string(fields, "world", line)
        val world: World = service.plugin.server.getWorld(worldName) ?: throw Reject(line, "world '${show(worldName)}' is not loaded")
        val action = string(fields, "action", line)
        @Suppress("UNCHECKED_CAST")
        val args = (fields["args"]?.let { node ->
            if (node !is MappingNode) throw Reject(line(node), "'args' must be a map")
            plain(node)
        } ?: emptyMap<String, Any?>()) as Map<String, Any?>

        return when (kind) {
            "block-click" -> {
                val at = xyz(fields, "at", line)
                val key = BlockKey(world.uid, at[0], at[1], at[2])
                if (!key.isPackable) throw Reject(line, "'at' is outside the world's block range")
                val options = ClickOptions.builder()
                    .sides(side(fields, line))
                    .hands(hands(fields, line))
                    .cancelEvent(bool(fields, "cancel", false, line))
                    .ignoreCancelled(bool(fields, "ignore-cancelled", true, line))
                    .build()
                val handler = resolve(line, kind, action) { actions.blockClick(action, args) }
                Spec(line, kind, action) { service.onBlockClick(owner, key, handler, options) }
            }
            "block-break" -> {
                val at = xyz(fields, "at", line)
                val key = BlockKey(world.uid, at[0], at[1], at[2])
                if (!key.isPackable) throw Reject(line, "'at' is outside the world's block range")
                val options = BreakOptions.builder().ignoreCancelled(bool(fields, "ignore-cancelled", true, line)).build()
                val handler = resolve(line, kind, action) { actions.blockBreak(action, args) }
                Spec(line, kind, action) { service.onBlockBreak(owner, key, handler, options) }
            }
            else -> {
                val min = xyz(fields, "min", line)
                val max = xyz(fields, "max", line)
                val riders = when (val r = optString(fields, "riders", line)?.lowercase(Locale.ROOT)) {
                    null, "skip" -> RiderPolicy.SKIP
                    "allow" -> RiderPolicy.ALLOW
                    "only" -> RiderPolicy.ONLY
                    else -> throw Reject(line, "riders must be skip, allow or only, not '${show(r)}'")
                }
                val options = AreaOptions.builder()
                    .riders(riders)
                    .includeSpectators(bool(fields, "spectators", false, line))
                    .passThrough(bool(fields, "pass-through", false, line))
                    .fireOnJoin(bool(fields, "fire-on-join", true, line))
                    .fireOnRegister(bool(fields, "fire-on-register", false, line))
                    .stepEvents(bool(fields, "step-events", false, line))
                    .build()
                val area = try {
                    Area.box(world, min[0], min[1], min[2], max[0], max[1], max[2])
                } catch (e: IllegalArgumentException) {
                    throw Reject(line, e.message ?: "invalid area")
                }
                val handler = resolve(line, kind, action) { actions.area(action, args) }
                Spec(line, kind, action) { service.area(owner, area, handler, options) }
            }
        }
    }

    private inline fun <T : Any> resolve(line: Int, kind: String, action: String, block: () -> T?): T {
        val handler = try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            throw Reject(line, "action '${show(action)}' failed to resolve: ${show(t.message ?: t.toString())}")
        }
        return handler ?: throw Reject(line, "unknown action '${show(action)}' for kind $kind")
    }

    private fun side(fields: Map<String, Node>, line: Int): ClickSide =
        when (val s = optString(fields, "side", line)?.lowercase(Locale.ROOT)) {
            null, "right" -> ClickSide.RIGHT
            "left" -> ClickSide.LEFT
            "both" -> ClickSide.BOTH
            "physical" -> ClickSide.PHYSICAL
            else -> throw Reject(line, "side must be right, left, both or physical, not '${show(s)}'")
        }

    private fun hands(fields: Map<String, Node>, line: Int): Set<EquipmentSlot> =
        when (val h = optString(fields, "hands", line)?.lowercase(Locale.ROOT)) {
            null, "main" -> setOf(EquipmentSlot.HAND)
            "off" -> setOf(EquipmentSlot.OFF_HAND)
            "both" -> setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)
            else -> throw Reject(line, "hands must be main, off or both, not '${show(h)}'")
        }

    private fun string(fields: Map<String, Node>, key: String, line: Int): String =
        optString(fields, key, line) ?: throw Reject(line, "missing '$key'")

    private fun optString(fields: Map<String, Node>, key: String, line: Int): String? {
        val node = fields[key] ?: return null
        val s = (node as? ScalarNode)?.value ?: throw Reject(line(node), "'$key' must be a single value")
        if (s.isBlank()) throw Reject(line(node), "'$key' is empty")
        return s
    }

    private fun bool(fields: Map<String, Node>, key: String, default: Boolean, line: Int): Boolean {
        val node = fields[key] ?: return default
        val v = plain(node)
        return v as? Boolean ?: throw Reject(line(node), "'$key' must be true or false")
    }

    private fun xyz(fields: Map<String, Node>, key: String, line: Int): IntArray {
        val node = fields[key] ?: throw Reject(line, "missing '$key'")
        val values = (plain(node) as? List<*>)?.takeIf { it.size == 3 }
            ?: throw Reject(line(node), "'$key' must be [x, y, z]")
        return IntArray(3) {
            (values[it] as? Int) ?: throw Reject(line(node), "'$key' must be three whole numbers")
        }
    }

    companion object {
        private val COMMON = setOf("kind", "world", "action", "args")
        private val CLICK_KEYS = COMMON + setOf("at", "side", "hands", "cancel", "ignore-cancelled")
        private val BREAK_KEYS = COMMON + setOf("at", "ignore-cancelled")
        private val AREA_KEYS = COMMON + setOf(
            "min", "max", "riders", "spectators", "pass-through", "fire-on-join", "fire-on-register", "step-events",
        )

        /** At most this many values in one value tree after aliases are expanded (stops alias bombs). */
        const val MAX_VALUES = 10_000

        /** Files and lines beyond these are refused before parsing (see [loadOnce]). */
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_LINE_CHARS = 4096

        /** Longer numbers are rejected before any pattern runs: no coordinate or argument needs 64 characters. */
        private const val MAX_NUMBER_CHARS = 64

        // Both unambiguous (each character can match one way only), so matching is linear. The float pattern used to
        // let two adjacent digit runs split a number every way, which backtracked quadratically (16,000 digits: 1 s).
        private val DECIMAL_INT = Regex("[-+]?(0|[1-9][0-9_]*)")
        private val DECIMAL_FLOAT = Regex("[-+]?([0-9][0-9_]*(\\.[0-9_]*)?|\\.[0-9_]+)([eE][-+]?[0-9]+)?")

        /** A user-written value quoted in a rejection reason: at most [max] characters. */
        private fun show(v: String, max: Int = 48): String = if (v.length <= max) v else v.take(max) + "... (${v.length} chars)"

        /** 1-based line of a node. */
        private fun line(node: Node): Int = node.startMark.line + 1

        /** Expansion bookkeeping: collections being expanded (cycle check, by identity) and values so far (cap). */
        private class Expansion {
            val active = IdentityHashMap<Node, Boolean>()
            var values = 0
        }

        /**
         * A node as plain Java values: maps (insertion order), lists, strings, Int or Long, Double, Boolean, null.
         * Uses the tags SnakeYAML's resolver assigned, so `yes`/`no` are booleans and quoted numbers stay strings.
         * Aliases are expanded; a recursive alias or more than [MAX_VALUES] values rejects the entry.
         */
        private fun plain(node: Node): Any? = plain(node, Expansion())

        private fun plain(node: Node, x: Expansion): Any? {
            if (++x.values > MAX_VALUES) throw Reject(line(node), "value too large: more than $MAX_VALUES values after aliases")
            if (node is ScalarNode) return scalar(node)
            if (node !is MappingNode && node !is SequenceNode) return null
            if (x.active.put(node, true) != null) throw Reject(line(node), "recursive alias")
            try {
                return if (node is MappingNode) {
                    val m = LinkedHashMap<String, Any?>()
                    for (t in node.value) {
                        // Not Node.toString(): it prints aliased collections in full (2^n nodes for n doubling aliases).
                        val k = (t.keyNode as? ScalarNode)?.value ?: throw Reject(line(t.keyNode), "keys must be plain names")
                        m[k] = plain(t.valueNode, x)
                    }
                    m
                } else {
                    (node as SequenceNode).value.map { plain(it, x) }
                }
            } finally {
                x.active.remove(node)
            }
        }

        /**
         * Numbers must be plain decimal. YAML 1.1 also reads `010` as octal 8, `0x10` as hex and `1:20` as base 60;
         * such a value in a coordinate would silently land somewhere else, so it rejects the entry instead.
         */
        private fun scalar(node: ScalarNode): Any? {
            val v = node.value
            if ((node.tag == Tag.INT || node.tag == Tag.FLOAT) && v.length > MAX_NUMBER_CHARS) {
                throw Reject(line(node), "number '${show(v)}' is too long")
            }
            return when (node.tag) {
                Tag.NULL -> null
                Tag.BOOL -> v.lowercase(Locale.ROOT) in setOf("true", "yes", "on", "y")
                Tag.INT -> {
                    if (!DECIMAL_INT.matches(v)) throw Reject(line(node), notDecimal(v))
                    val n = v.replace("_", "").removePrefix("+").toLongOrNull() ?: throw Reject(line(node), "number '${show(v)}' is too large")
                    if (n in Int.MIN_VALUE..Int.MAX_VALUE) n.toInt() else n
                }
                Tag.FLOAT -> {
                    if (!DECIMAL_FLOAT.matches(v) || v.none { it.isDigit() }) throw Reject(line(node), notDecimal(v))
                    v.replace("_", "").toDoubleOrNull() ?: throw Reject(line(node), notDecimal(v))
                }
                else -> v
            }
        }

        private fun notDecimal(v: String) =
            "number '${show(v)}' must be written in plain decimal (no leading 0, 0x, 0o, 0b, ':', .inf or .nan); quote it for text"
    }
}
