package com.sneakyutils.triggers.service

import com.sneakyutils.integration.Probe

/**
 * Names for SneakyDiagnostics: which section or count a subscription's handler work is recorded under, within the
 * probe's limit of 256 names per kind (sections, counts, levels; more are dropped and make the report incomplete).
 *
 * A handler call is recorded as `handler.<owner>.<callback>` or, for a labelled subscription,
 * `handler.<owner>.<callback>.<label>`; its errors, slow calls and self-triggered skips as counts under the same suffix
 * (`handler.errors.<owner>.<callback>[.<label>]`, ...). Subscriptions never get a name of their own: all those with the
 * same owner, callback and label share one, so a name is a fixed label however many subscriptions exist. When a capture
 * meets more distinct names than [SECTION_PRIMARY] (or [COUNT_PRIMARY]) the rest fold into the unlabelled owner name,
 * then into `handler.other`; each fold is counted as `diagnostics.names-folded`, so a report says it was folded.
 *
 * Budgets reset at the start of each capture. Server thread only, like the probe. Nothing here runs outside a capture.
 */
internal class TriggerDiagnostics(private val probe: Probe?) {

    /** Bumped at each capture start; a subscription's cached names from an older capture are re-resolved. */
    @JvmField var epoch = 0L

    private val sections = NameBudget(SECTION_PRIMARY, SECTION_CAP)
    private val counts = NameBudget(COUNT_PRIMARY, COUNT_CAP)

    /** Owner/kind levels admitted this capture, reported every sample (0 when there are none now). */
    @JvmField val ownerLevels = LinkedHashMap<String, Long>()

    fun newCapture() {
        epoch++
        sections.reset()
        counts.reset()
        ownerLevels.clear()
    }

    /** The name [what] ([SECTION], [ERRORS], ...) for callback [cb] of [sub], resolved once per capture. */
    fun name(sub: SubBase, what: Int, cb: Int): String {
        var arr = sub.diagNames
        if (arr == null) {
            arr = arrayOfNulls(WHATS * SubBase.CALLBACKS)
            sub.diagNames = arr
            sub.diagEpoch = epoch
        } else if (sub.diagEpoch != epoch) {
            arr.fill(null)
            sub.diagEpoch = epoch
        }
        val i = what * SubBase.CALLBACKS + cb
        return arr[i] ?: resolve(sub, what, cb).also { arr[i] = it }
    }

    private fun resolve(sub: SubBase, what: Int, cb: Int): String {
        val prefix = PREFIXES[what]
        val callback = sub.callbackName(cb)
        val owner = "$prefix.${sub.ownerKey}.$callback"
        val primary = if (sub.label != null) "$owner.${sub.label}" else owner
        val budget = if (what == SECTION) sections else counts
        val folded = budget.folded
        val name = budget.admit(primary, owner, "$prefix.other")
        if (budget.folded != folded) probe?.count(FOLDED, 1)
        return name
    }

    /** Distinct names handed out this capture (sections, then counts), for tests. */
    internal val sectionNames: Int get() = sections.size
    internal val countNames: Int get() = counts.size

    /**
     * A set of names with a cap: the first [primaryCap] distinct names are admitted as asked; after that a name folds
     * into its fallback (admitted while there is room up to [cap]), and past [cap] into the overflow name.
     */
    private class NameBudget(private val primaryCap: Int, private val cap: Int) {
        private val names = HashSet<String>()
        var folded = 0L
        val size: Int get() = names.size

        fun reset() {
            names.clear()
            folded = 0
        }

        fun admit(primary: String, fallback: String, overflow: String): String {
            if (primary in names) return primary
            if (names.size < primaryCap) {
                names += primary
                return primary
            }
            folded++
            if (fallback in names) return fallback
            if (names.size < cap) {
                names += fallback
                return fallback
            }
            names += overflow
            return overflow
        }
    }

    companion object {
        const val SECTION = 0
        const val ERRORS = 1
        const val SLOW = 2
        const val SELF_SKIPPED = 3
        const val DROPPED_AFTER = 4
        private const val WHATS = 5
        private val PREFIXES = arrayOf("handler", "handler.errors", "handler.slow", "handler.self-skipped", "queue.dropped.after")

        /**
         * Section budget for handler names. The engine has 14 fixed sections and `handler.other` is the overflow:
         * 14 + 224 + 1 stays under the probe's 256.
         */
        const val SECTION_PRIMARY = 192
        const val SECTION_CAP = 224

        /** Count budget: about 30 fixed counts, four overflow names, 200 handler names. */
        const val COUNT_PRIMARY = 160
        const val COUNT_CAP = 200

        /** Owner/kind subscription levels: the first [OWNER_LEVELS] pairs, the rest summed in `subscriptions.by-owner.other`. */
        const val OWNER_LEVELS = 200

        const val FOLDED = "diagnostics.names-folded"
    }
}
