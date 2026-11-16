package com.sneakyutils.triggers

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics

/**
 * Records what the shim reports, like SneakyDiagnostics' recorder would. [capturing] plays the part of a running
 * capture. With [record] off it records nothing (for allocation and cost tests). Sections are checked for strict
 * nesting as the real recorder does ([nestingErrors]).
 */
internal class FakeProbe : Diagnostics.Probe {
    var capturing = false
    var due = false
    var record = true
    var onCalls = 0
    var startCalls = 0
    var nestingErrors = 0
    private var next = 0L
    private val open = ArrayList<Long>()
    val sections = ArrayList<String>()
    val counts = HashMap<String, Long>()
    val levels = HashMap<String, Long>()

    override fun start(): Long {
        startCalls++
        if (!capturing) return 0L
        val t = ++next
        if (record) open += t
        return t
    }

    override fun end(section: String, token: Long) {
        if (token == 0L || !record) return
        if (open.isEmpty() || open[open.size - 1] != token) nestingErrors++ else open.removeAt(open.size - 1)
        sections += section
    }

    override fun count(key: String, n: Long) {
        if (record) counts.merge(key, n, Long::plus)
    }

    override fun sampleDue(): Boolean = due
    override fun level(key: String, value: Long) {
        if (record) levels[key] = value
    }

    override fun on(): Boolean {
        onCalls++
        return capturing
    }

    override fun close() {}
}

internal class FakeDiagnostics(val probe: FakeProbe) : Diagnostics {
    var instrumentation: String? = null
    override fun register(plugin: String, version: String, instrumentation: String, provider: Diagnostics.Provider): Diagnostics.Registration =
        throw UnsupportedOperationException()

    override fun probe(plugin: String, pluginVersion: String, instrumentation: String): Diagnostics.Probe {
        this.instrumentation = instrumentation
        return probe
    }
}
