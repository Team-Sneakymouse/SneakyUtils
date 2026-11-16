package com.sneakyutils.triggers

import java.io.File

/**
 * Turns a table entry's named action into a handler for [Triggers.loadTable]. Implement the kinds your tables use;
 * every method defaults to null, which rejects the entry as an unknown action.
 *
 * [args] is the entry's `args:` map as YAML gives it (strings, numbers, booleans, lists and maps), empty when absent.
 * A resolver may throw to reject an entry; the exception message becomes the rejection reason.
 */
interface ActionResolver {
    fun blockClick(action: String, args: Map<String, Any?>): BlockClickHandler? = null
    fun blockBreak(action: String, args: Map<String, Any?>): BlockBreakHandler? = null
    fun area(action: String, args: Map<String, Any?>): AreaHandler? = null
}

/** One table entry that loaded: its 1-based line in the file, its kind, its action, and its subscription. */
class TableEntry(val line: Int, val kind: String, val action: String, val subscription: Subscription) {
    override fun toString(): String = "TableEntry(line=$line, kind=$kind, action=$action)"
}

/** One table entry that didn't load, with its 1-based line (0 for the whole file) and why. */
class RejectedEntry(val line: Int, val reason: String) {
    override fun toString(): String = "RejectedEntry(line=$line, reason=$reason)"
}

/**
 * What [Triggers.loadTable] did.
 *
 * [replacedPrevious] is false when the file couldn't be read or parsed at all: then nothing was loaded, the one
 * rejected entry describes the error, and the subscriptions of an earlier load of the same file stay active.
 */
class TableLoadResult(
    val file: File,
    val loaded: List<TableEntry>,
    val rejected: List<RejectedEntry>,
    val replacedPrevious: Boolean,
) {
    /** The subscriptions of the loaded entries, in file order. */
    val subscriptions: List<Subscription> get() = loaded.map { it.subscription }

    override fun toString(): String =
        "TableLoadResult(file=${file.name}, loaded=${loaded.size}, rejected=$rejected, replacedPrevious=$replacedPrevious)"
}
