package com.sneakyutils.triggers

import com.sneakyutils.SneakyUtils
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.lang.management.ManagementFactory
import java.util.SplittableRandom

/**
 * Fuzzes [Triggers.loadTable] with random, mutated and pathological YAML: aliases, deep nesting, huge numbers, wrong
 * types, unicode, empty and enormous files. Every load must return (never throw), within a time budget, allocating at
 * most a bounded multiple of the file size, with short rejection reasons.
 *
 * `-Dtriggers.soak=N` multiplies the random rounds (see build.gradle.kts).
 */
class TableFuzzTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var triggers: Triggers

    @TempDir
    lateinit var dir: File

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Resolves most actions; `none*` is unknown, `boom*` throws. */
    private val resolver = object : ActionResolver {
        private fun check(action: String) {
            if (action.startsWith("boom")) error("resolver failed for $action")
        }

        override fun blockClick(action: String, args: Map<String, Any?>): BlockClickHandler? {
            check(action)
            return if (action.startsWith("none")) null else BlockClickHandler { _, _, _, _ -> false }
        }

        override fun blockBreak(action: String, args: Map<String, Any?>): BlockBreakHandler? {
            check(action)
            return if (action.startsWith("none")) null else BlockBreakHandler { _, _, _ -> }
        }

        override fun area(action: String, args: Map<String, Any?>): AreaHandler? {
            check(action)
            return if (action.startsWith("none")) null else object : AreaHandler {}
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        server.addSimpleWorld("world")
        server.addSimpleWorld("wörld ☃")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
        server.addPlayer("p")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    /** Loads [bytes] as the table file and checks the contract. */
    private fun load(bytes: ByteArray, label: String, budgetMillis: Long = 2_000, extraBytes: Long = 0): TableLoadResult {
        val f = File(dir, "table.yml")
        f.writeBytes(bytes)
        val a0 = allocated()
        val t0 = System.nanoTime()
        val r = try {
            triggers.loadTable(owner, f, resolver)
        } catch (t: Throwable) {
            fail("loadTable threw on $label: $t\n--- input (first 300 chars) ---\n${preview(bytes)}", t)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val alloc = allocated() - a0
        if (bytes.size > 200_000) println("TableFuzzTest load: $label, ${bytes.size / 1024} KiB, $ms ms, ${alloc / 1024 / 1024} MB allocated")
        assertTrue(ms <= budgetMillis, "$label took $ms ms (budget $budgetMillis)\n${preview(bytes)}")
        // SnakeYAML allocates about 250 bytes per input byte, all short-lived; the file is capped at 2 MiB.
        val bound = 320L * bytes.size + 64L * 1024 * 1024 + extraBytes
        assertTrue(alloc <= bound, "$label allocated ${alloc / 1024 / 1024} MB for ${bytes.size} bytes\n${preview(bytes)}")
        for (rej in r.rejected) {
            assertTrue(rej.reason.length <= 400, "$label: a ${rej.reason.length}-char rejection reason: ${rej.reason.take(200)}")
            assertTrue(rej.line >= 0)
        }
        if (!r.replacedPrevious) assertTrue(r.loaded.isEmpty() && r.rejected.size == 1, "$label: a file error has one reason")
        for (e in r.loaded) assertTrue(e.subscription.active)
        return r
    }

    private fun load(text: String, label: String, budgetMillis: Long = 2_000, extraBytes: Long = 0) =
        load(text.toByteArray(Charsets.UTF_8), label, budgetMillis, extraBytes)

    private fun preview(bytes: ByteArray) = String(bytes, 0, minOf(bytes.size, 300), Charsets.UTF_8)

    private val rounds: Int get() = (System.getProperty("triggers.soak")?.toLongOrNull()?.coerceIn(1, 100_000)?.toInt() ?: 1)

    // ---------------------------------------------------------------- random documents

    @Test
    fun randomDocumentsNeverThrowHangOrBlowUp() {
        val n = 1_500 * rounds
        var loadedEntries = 0
        var rejectedEntries = 0
        var fileErrors = 0
        for (i in 0 until n) {
            val text = YamlGen(i.toLong() * 7919 + 1).document()
            val r = load(text, "random document $i")
            loadedEntries += r.loaded.size
            rejectedEntries += r.rejected.size
            if (!r.replacedPrevious) fileErrors++
        }
        println("TableFuzzTest random: $n documents, $loadedEntries entries loaded, $rejectedEntries rejected, $fileErrors file errors")
        assertTrue(loadedEntries > n / 4, "the generator must produce loadable entries too")
        assertTrue(rejectedEntries > n / 4 && fileErrors > n / 50, "and every kind of failure")
    }

    @Test
    fun mutatedValidTablesNeverThrow() {
        val r = SplittableRandom(42)
        val seeds = listOf(VALID, VALID_BLOCK, VALID_ENTRIES)
        val alphabet = "[]{}:,-&*!|>'\"#%@?<\t\n \\0x.eE+_~☃\u0000\uFEFF\u202Eaz019"
        for (i in 0 until 1_500 * rounds) {
            val sb = StringBuilder(seeds[r.nextInt(seeds.size)])
            repeat(1 + r.nextInt(6)) {
                if (sb.isEmpty()) return@repeat
                val at = r.nextInt(sb.length)
                when (r.nextInt(4)) {
                    0 -> sb.deleteCharAt(at)
                    1 -> sb.insert(at, alphabet[r.nextInt(alphabet.length)])
                    2 -> sb.setCharAt(at, alphabet[r.nextInt(alphabet.length)])
                    else -> { // duplicate a slice (repeated keys, entries, brackets)
                        val end = minOf(sb.length, at + 1 + r.nextInt(40))
                        sb.insert(at, sb.substring(at, end))
                    }
                }
            }
            load(sb.toString(), "mutation $i")
        }
    }

    // ---------------------------------------------------------------- pathological documents

    @Test
    fun pathologicalDocuments() {
        val cases = LinkedHashMap<String, ByteArray>()
        fun add(name: String, text: String) {
            cases[name] = text.toByteArray(Charsets.UTF_8)
        }
        add("empty", "")
        add("whitespace", "  \n\n\t\n")
        add("comment only", "# nothing\n")
        add("byte order mark", "\uFEFF$VALID")
        add("document markers", "---\n...\n")
        add("two documents", "$VALID\n---\n$VALID")
        add("root scalar", "hello")
        add("root number", "42")
        add("root null", "~")
        add("entries scalar", "entries: 5")
        add("entries null", "entries:")
        add("entries map", "entries: {a: 1}")
        add("nul bytes", "- {kind: area\u0000, world: world}\n\u0000\u0000")
        add("tabs as indentation", "-\n\tkind: area\n\tworld: world")
        add("deep flow nesting", "[".repeat(100_000) + "]".repeat(100_000))
        add("deep map nesting", "{a: ".repeat(20_000) + "1" + "}".repeat(20_000))
        add("deep block nesting", (0 until 2_000).joinToString("\n") { "  ".repeat(it) + "- " })
        add("deep args", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: ${"[".repeat(45)}1${"]".repeat(45)}}")
        add("recursive root", "&a [*a]")
        add("recursive entries", "entries: &e [*e]")
        add("recursive args", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: &r {x: *r}}")
        add("billion laughs", billionLaughs(9, 9))
        add("alias doubling under the composer limit", aliasDoubling(23, "args:\n    big: *a23"))
        add("aliased complex key", aliasDoubling(22, "args:\n    ? *a22\n    : boom"))
        add("aliased merge key", aliasDoubling(22, "args:\n    <<: *m\n    x: 1").replace("a0: &a0 [x, x]", "m: &m {k: *a22}\n  a0: &a0 [x, x]"))
        add("aliased world", "- &w {kind: block-click, world: world, at: [1, 2, 3], action: a}\n" + (0 until 49).joinToString("\n") { "- *w" })
        add("huge integer", "- {kind: block-click, world: world, at: [${"9".repeat(2_000_000)}, 1, 1], action: a}")
        add("long integer", "- {kind: block-click, world: world, at: [${"9".repeat(4_000)}, 1, 1], action: a}")
        add("long explicit float", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: !!float \"${"1".repeat(4_000)}x\"}}")
        add("long key", "- {kind: area, world: world, ${"k".repeat(4_000)}: 1}")
        add("long action", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: none${"a".repeat(4_000)}}")
        add("many long lines", (0 until 400).joinToString("\n") { "- {kind: block-click, world: world, at: [$it, 1, 1], action: ${"a".repeat(4_000)}}" })
        add("huge sexagesimal float", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: ${"1".repeat(2_000_000)}:30.5}}")
        add("huge explicit float", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: !!float \"${"1".repeat(2_000_000)}x\"}}")
        add("huge explicit int", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: !!int \"${"1".repeat(2_000_000)}x\"}}")
        add("huge key", "- {kind: area, world: world, ${"k".repeat(2_000_000)}: 1}")
        add("huge action", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: none${"a".repeat(2_000_000)}}")
        add("huge world name", "- {kind: area, world: ${"w".repeat(2_000_000)}, min: [0,0,0], max: [1,1,1], action: a}")
        add("huge riders", "- {kind: area, world: world, min: [0,0,0], max: [1,1,1], riders: ${"x".repeat(2_000_000)}, action: a}")
        add("long line", "a".repeat(2_900_000))
        add("over the code point limit", VALID.repeat(4_000_000 / VALID.length + 1))
        add("extreme coordinates", """
            - {kind: block-click, world: world, at: [2147483647, 2147483647, 2147483647], action: a}
            - {kind: block-click, world: world, at: [-2147483648, 0, 0], action: a}
            - {kind: block-click, world: world, at: [33554431, 2047, -33554432], action: a}
            - {kind: area, world: world, min: [-2147483648, -2147483648, -2147483648], max: [2147483647, 2147483647, 2147483647], action: a}
            - {kind: area, world: world, min: [2147483632, -2147483648, 2147483632], max: [2147483647, 2147483647, 2147483647], action: a}
            - {kind: area, world: world, min: [0, 0, 0], max: [1023, 0, 1023], action: a}
            - {kind: area, world: world, min: [0, 0, 0], max: [1024, 0, 1023], action: a}
        """.trimIndent())
        add("wrong types", """
            - {kind: [area], world: world}
            - {kind: area, world: {a: 1}, min: 5, max: "x", action: [a]}
            - {kind: area, world: world, min: [1.5, 2, 3], max: [1, 2, true], action: a}
            - {kind: block-click, world: world, at: [1, 2, null], action: a, cancel: maybe, side: 7}
            - {kind: block-click, world: world, at: [1, 2, 3], action: a, args: [1, 2]}
            - {kind: block-click, world: world, at: [1, 2, 3], action: a, hands: [main]}
            - {kind: area, world: world, min: [0, 0, 0], max: [1, 1, 1], action: a, pass-through: 1}
            - [kind, area]
            - 17
            - ~
            - {? [kind] : area}
            - {kind: block-click, kind: block-break, world: world, at: [1, 2, 3], action: a}
        """.trimIndent())
        add("unicode", """
            - {kind: block-click, world: "wörld ☃", at: [1, 2, 3], action: "door 🚪", args: {"名前": "値", "\u202Eevil": "x", "e\u0301": 1}}
            - {kind: area, world: "wörld ☃", min: [0, 0, 0], max: [1, 1, 1], action: "\uD83D\uDE00", riders: "ＡＬＬＯＷ"}
            - {kind: block-click, world: world, at: [1, 2, 3], action: "\ud800"}
        """.trimIndent())
        add("tags", """
            - {kind: block-click, world: world, at: [!!int 1, !!int "2", 3], action: !!str a, args: {b: !!binary aGVsbG8=, t: !!timestamp 2001-12-14, s: !!set {a, b}, o: !!omap [a: 1], c: !custom x, f: !!float 1, n: !!null x, bo: !!bool maybe}}
            - {kind: !!str area, world: !foo world, min: !!seq [0, 0, 0], max: [1, 1, 1], action: a}
        """.trimIndent())
        add("number forms", """
            - {kind: block-click, world: world, at: [010, 0x10, 0b11], action: a}
            - {kind: block-click, world: world, at: [1:30, +1, -0], action: a}
            - {kind: block-click, world: world, at: [1_0, 1e3, .5], action: a, args: {a: .inf, b: -.inf, c: .nan, d: 1e309, e: -0.0, f: 0o17, g: 1:20:30.5}}
        """.trimIndent())
        val bytes = LinkedHashMap<String, ByteArray>(cases)
        bytes["invalid utf-8"] = byteArrayOf(0x2D, 0x20, 0x7B, 0xFF.toByte(), 0xFE.toByte(), 0xC0.toByte(), 0x80.toByte(), 0x7D, 0x0A)
        bytes["utf-16 file"] = VALID.toByteArray(Charsets.UTF_16)
        bytes["cesu surrogate"] = "- {kind: area, world: world, action: \"".toByteArray() + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "\"}".toByteArray()
        for ((name, b) in bytes) load(b, name, budgetMillis = 5_000)
    }

    @Test
    fun manyEntriesLoadAndReplaceWithoutQuadraticBlowUp() {
        // Near the 2 MiB file limit: 30,000 distinct clicks, then the same table again (closing all of them).
        val distinct = (0 until 30_000).joinToString("\n") { "- {kind: block-click, world: world, at: [$it, 64, 0], action: a}" }
        assertEquals(30_000, load(distinct, "30k distinct clicks", 10_000).loaded.size)
        assertEquals(30_000, load(distinct, "30k distinct clicks, reloaded", 10_000).loaded.size)
        // Subscriptions on one key are copy-on-write arrays: registering or closing n of them copies O(n^2) entries
        // (20,000 on one block: 1 s and 2.6 GB of garbage to reload). 5,000 on one block or in one chunk, far beyond
        // any real table, stay inside the budget.
        val same = (0 until 5_000).joinToString("\n") { "- {kind: block-click, world: world, at: [5, 64, 5], action: a}" }
        val quadratic = 400L * 1024 * 1024 // 5,000^2 array slots, copied on add and on close
        load(same, "5k clicks on one block", 5_000, quadratic)
        load(same, "5k clicks on one block, reloaded", 5_000, quadratic)
        val dense = (0 until 5_000).joinToString("\n") { "- {kind: area, world: world, min: [${it % 16}, 60, 0], max: [15, 70, ${it % 16}], action: a}" }
        load(dense, "5k areas in one chunk", 5_000, quadratic)
        load(dense, "5k areas in one chunk, reloaded", 5_000, quadratic)
        load("", "empty after all that")
        assertEquals(0, triggers.stats().byKind.values.sum())
    }

    // ---------------------------------------------------------------- regressions found by the fuzzing above

    @Test
    fun longNumbersAreCheckedInLinearTime() {
        // DECIMAL_FLOAT backtracked quadratically on an explicitly tagged float: 16,000 digits and a stray letter took
        // a second, 2 MB would have taken hours on the server thread.
        val forms = listOf("!!float \"${"1".repeat(4_000)}x\"", "!!float \"${"1".repeat(4_000)}:30.5\"", "!!int \"${"1".repeat(4_000)}\"")
        for (form in forms) {
            val r = load("- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: $form}}", form.take(12), 300)
            assertTrue(r.rejected.single().reason.contains("too long"), r.rejected.toString())
        }
        val r = load("- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: a, args: {n: !!float \"${"1".repeat(60)}x\"}}", "short", 300)
        assertTrue(r.rejected.single().reason.contains("decimal"), r.rejected.toString())
    }

    @Test
    fun longLinesAndBigFilesAreRefusedBeforeParsing() {
        // SnakeYAML's reader is quadratic in token length: a 2 MB token allocated 7.5 GB.
        val long = load("- {kind: block-click, world: world, at: [${"9".repeat(2_000_000)}, 1, 1], action: a}", "2 MB token")
        assertFalse(long.replacedPrevious)
        assertTrue(long.rejected.single().reason.contains("longer than"), long.rejected.toString())
        val big = load(VALID.repeat(3 * 1024 * 1024 / VALID.length), "3 MiB file")
        assertTrue(big.rejected.single().reason.contains("larger than"), big.rejected.toString())
    }

    @Test
    fun anAliasedComplexKeyIsRejectedNotPrinted() {
        // plain() named a non-scalar key with Node.toString(), which prints aliases in full: 2^22 nodes.
        val r = load(aliasDoubling(22, "args:\n    ? *a22\n    : boom"), "aliased complex key", 2_000)
        assertTrue(r.rejected.any { it.reason.contains("plain names") }, r.rejected.toString())
    }

    @Test
    fun rejectionReasonsQuoteAtMostAShortPrefix() {
        val r = load("- {kind: area, world: world, min: [0,0,0], max: [1,1,1], action: none${"a".repeat(100_000)}}", "long action")
        assertTrue(r.rejected.single().reason.length < 200, r.rejected.single().reason.take(300))
    }

    @Test
    fun aFileErrorKeepsThePreviousLoadAndAGoodFileReplacesIt() {
        assertEquals(1, load(VALID, "valid").loaded.size)
        assertFalse(load("[", "broken").replacedPrevious)
        assertEquals(1, triggers.stats().count("block-click"))
        load(VALID_ENTRIES, "entries map")
        assertEquals(1, triggers.stats().count("area"))
        assertEquals(0, triggers.stats().count("block-click"))
    }

    companion object {
        const val VALID = "- {kind: block-click, world: world, at: [10, 64, -3], side: right, action: door, args: {code: 1234}}\n"
        val VALID_BLOCK = """
            - kind: area
              world: world
              min: [0, 60, 0]
              max: [5, 70, 5]
              riders: allow
              pass-through: true
              action: forge
              args:
                heat: 3
                tags: [hot, loud]
            - kind: block-break
              world: world
              at: [11, 64, -3]
              action: ore
        """.trimIndent()
        val VALID_ENTRIES = """
            entries:
              - {kind: area, world: world, min: [0, 60, 0], max: [5, 70, 5], step-events: yes, action: forge}
        """.trimIndent()

        /** Anchors a0..a[levels], each a list of two aliases of the previous, then [use] inside one entry. */
        fun aliasDoubling(levels: Int, use: String): String = buildString {
            appendLine("defs:")
            appendLine("  a0: &a0 [x, x]")
            for (i in 1..levels) appendLine("  a$i: &a$i [*a${i - 1}, *a${i - 1}]")
            appendLine("entries:")
            appendLine("  - kind: area")
            appendLine("    world: world")
            appendLine("    min: [0, 0, 0]")
            appendLine("    max: [1, 1, 1]")
            appendLine("    action: a")
            append("    ").appendLine(use)
        }

        fun billionLaughs(levels: Int, fan: Int): String = buildString {
            appendLine("defs:")
            appendLine("  l0: &l0 [lol]")
            for (i in 1..levels) appendLine("  l$i: &l$i [${(0 until fan).joinToString(", ") { "*l${i - 1}" }}]")
            appendLine("entries:")
            appendLine("  - {kind: area, world: world, min: [0, 0, 0], max: [1, 1, 1], action: a, args: {x: *l$levels}}")
        }
    }
}

/** Random YAML, mostly table-shaped, often broken. */
private class YamlGen(seed: Long) {
    private val r = SplittableRandom(seed)
    private val anchors = ArrayList<String>()
    private var nextAnchor = 0

    fun document(): String = buildString {
        val n = r.nextInt(12)
        val asEntries = r.nextInt(8) == 0
        if (asEntries) append("entries:\n")
        for (i in 0 until n) {
            if (asEntries) append("  ")
            append("- ").append(entry()).append('\n')
        }
        if (r.nextInt(20) == 0) append(value(0)) // trailing junk
    }

    private fun entry(): String {
        if (r.nextInt(5) < 2) return validEntry()
        if (r.nextInt(15) == 0) return value(0)
        val kind = pick("block-click", "block-break", "area", "area", "teleporter", "Area", "")
        val keys = LinkedHashMap<String, String>()
        if (r.nextInt(20) != 0) keys["kind"] = scalarOr(kind)
        if (r.nextInt(20) != 0) keys["world"] = scalarOr(pick("world", "world", "\"wörld ☃\"", "nether", "\"\""))
        if (r.nextInt(20) != 0) keys["action"] = scalarOr(pick("door", "forge", "none-x", "boom", "\"🚪\"", "a b"))
        if (r.nextInt(3) == 0) keys["args"] = if (r.nextInt(3) == 0) value(0) else (0 until r.nextInt(4)).joinToString(", ", "{", "}") { "${key()}: ${value(1)}" }
        when (kind) {
            "block-click", "block-break" -> {
                keys["at"] = triple()
                if (r.nextInt(3) == 0) keys["side"] = scalarOr(pick("right", "left", "both", "physical", "up"))
                if (r.nextInt(4) == 0) keys["hands"] = scalarOr(pick("main", "off", "both", "feet"))
                if (r.nextInt(4) == 0) keys["cancel"] = scalarOr(pick("true", "false", "yes", "no", "maybe"))
            }
            else -> {
                keys["min"] = triple()
                keys["max"] = triple()
                for (k in listOf("riders", "spectators", "pass-through", "fire-on-join", "fire-on-register", "step-events")) {
                    if (r.nextInt(4) == 0) keys[k] = scalarOr(pick("true", "false", "on", "skip", "allow", "only", "7"))
                }
            }
        }
        if (r.nextInt(10) == 0) keys[pick("sid", "extra", "<<", "Kind", "kind ")] = value(1)
        val entries = keys.entries.toMutableList()
        if (r.nextInt(10) == 0) entries.shuffle(java.util.Random(r.nextLong()))
        return entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
    }

    /** An entry that should load, with random valid options and arguments. */
    private fun validEntry(): String {
        fun c() = (r.nextInt(2000) - 1000).toString()
        val world = pick("world", "\"wörld ☃\"")
        val args = if (r.nextBoolean()) "" else ", args: " + (0 until r.nextInt(4)).joinToString(", ", "{", "}") { "${key()}: ${value(1)}" }
        return when (r.nextInt(3)) {
            0 -> "{kind: block-click, world: $world, at: [${c()}, ${r.nextInt(300)}, ${c()}], action: door$args" +
                (if (r.nextBoolean()) ", side: ${pick("right", "left", "both", "physical")}" else "") +
                (if (r.nextBoolean()) ", hands: ${pick("main", "off", "both")}" else "") +
                (if (r.nextBoolean()) ", cancel: ${pick("true", "false", "yes", "off")}" else "") + "}"
            1 -> "{kind: block-break, world: $world, at: [${c()}, ${r.nextInt(300)}, ${c()}], action: ore$args}"
            else -> "{kind: area, world: $world, min: [${c()}, ${r.nextInt(300)}, ${c()}], max: [${r.nextInt(300)}, ${r.nextInt(300)}, ${r.nextInt(300)}], " +
                "action: forge$args" + (if (r.nextBoolean()) ", riders: ${pick("skip", "allow", "only", "ALLOW")}" else "") +
                listOf("spectators", "pass-through", "fire-on-join", "fire-on-register", "step-events")
                    .filter { r.nextInt(3) == 0 }.joinToString("") { ", $it: ${pick("true", "false", "yes", "no")}" } + "}"
        }
    }

    private fun triple(): String = when (r.nextInt(8)) {
        0 -> value(1)
        1 -> "[${number()}, ${number()}]"
        else -> "[${number()}, ${number()}, ${number()}]"
    }

    private fun number(): String = if (r.nextInt(5) != 0) (r.nextInt(2000) - 1000).toString() else when (r.nextInt(14)) {
        0 -> "2147483647"
        1 -> "-2147483648"
        2 -> "99999999999999999999"
        3 -> "010"
        4 -> "0x1F"
        5 -> "1:20"
        6 -> "1.5"
        7 -> "1e3"
        8 -> ".inf"
        9 -> "33554432"
        else -> (r.nextInt(2000) - 1000).toString()
    }

    private fun scalarOr(s: String) = if (r.nextInt(12) == 0) value(1) else s

    private fun value(depth: Int): String {
        if (anchors.isNotEmpty() && r.nextInt(10) == 0) return "*" + anchors[r.nextInt(anchors.size)]
        val anchored = depth < 4 && r.nextInt(10) == 0
        val v = when (if (depth > 3) 0 else r.nextInt(4)) {
            1 -> (0 until r.nextInt(5)).joinToString(", ", "[", "]") { value(depth + 1) }
            2 -> (0 until r.nextInt(5)).joinToString(", ", "{", "}") { "${key()}: ${value(depth + 1)}" }
            else -> scalar()
        }
        if (!anchored) return v
        val name = "n${nextAnchor++}"
        anchors += name
        return "&$name $v"
    }

    private fun key(): String = when (r.nextInt(10)) {
        0 -> "? [a, b]"
        1 -> "\"ключ\""
        2 -> "<<"
        else -> "k${r.nextInt(6)}"
    }

    private fun scalar(): String = when (r.nextInt(16)) {
        0 -> number()
        1 -> pick("yes", "no", "on", "off", "y", "n", "True", "FALSE")
        2 -> pick("~", "null", "")
        3 -> "\"" + pick("☃", "名前", "\\u0000", "\\uD800", "\\x41", "e\u0301", "\u202Eabc", "tab\\there") + "\""
        4 -> "'single ''quoted'''"
        5 -> pick("!!int 5", "!!int x", "!!float 1", "!!float .x", "!!str 5", "!!binary aGVsbG8=", "!!timestamp 2001-12-14", "!foo bar", "!!bool 3")
        6 -> pick("1_000", "+12", "-0", "0b101", "0o17", "1:20:30.5", ".5", "1.", "6.02e23", "-.inf", ".NaN")
        7 -> "a".repeat(r.nextInt(300))
        else -> "w${r.nextInt(100)}"
    }

    private fun pick(vararg s: String) = s[r.nextInt(s.size)]
}
