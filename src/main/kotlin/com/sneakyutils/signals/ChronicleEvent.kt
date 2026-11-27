package com.sneakyutils.signals

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Something happened that the admins will want to read about later: an orb changed hands, a character levelled up,
 * someone said something. Plugins report it here; a recorder plugin (SneakyChronicle) listens and stores it. Nobody
 * depends on the recorder, and on a server without one a report does nothing.
 *
 * ```java
 * ChronicleEvent.of("sneakylegendarys.orb_placed").actor(player).thing("orb", 3).detail("where", "shrine").fire();
 * ```
 *
 * Reporting works on any thread and never throws: the event is asynchronous ([isAsynchronous]) when it was reported
 * off the server thread, so a listener must not touch Bukkit state without checking. A report with an invalid
 * [type] is dropped, an invalid part of one (a malformed thing or detail key) is left out, and both log one warning.
 *
 * Everything on the event is immutable. Usage and rules for plugin authors: `docs/chronicle.md`.
 */
class ChronicleEvent private constructor(builder: Builder, async: Boolean) : Event(async) {
    /** Made when the event was reported; the stable reference to this event for everything that reads it later. */
    val id: UUID = UUID.randomUUID()

    /** Stable id of what happened, `<owner>.<what_happened>`, such as `sneakyleveling.level_up` or `chat.message`. */
    val type: String = builder.type

    /** When it happened, or when it began for something with a duration. */
    val start: Instant = builder.start

    /** When it ended, for something with a duration (speech); null for a moment. Never before [start]. */
    val end: Instant? = builder.end

    /** Who did it, or null when nobody did. */
    val actor: Subject? = builder.actor

    /** Who it was done to. */
    val targets: List<Subject> = java.util.List.copyOf(builder.targets)

    /** The named things it was about (`orb` `3`, `plot` `brickton-brickback`), so one thing's history can be read. */
    val things: List<Thing> = java.util.List.copyOf(builder.things)

    /** Extra facts. Values are `String`, `Long`, `Double` or `Boolean`. */
    val details: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.details))

    /**
     * What a player said or typed, when that is what happened. The recorder's rules about what is never stored
     * (out-of-character lines, private places) apply to every event that has text, whatever its type.
     */
    val text: String? = builder.text

    /**
     * Accounts of the players who heard or saw [text], as the reporter knows them; null when the reporter did not
     * say (the recorder may then work it out), empty when it said nobody did.
     */
    val hearers: List<UUID>? = builder.hearers?.let { java.util.List.copyOf(it) }

    /** Where it happened, when the reporter said so; null leaves it to the recorder (the actor's position). */
    val place: Place? = builder.place

    override fun getHandlers(): HandlerList = HANDLERS

    /**
     * A player account, a character or a named actor: exactly one of the three is set.
     *
     * @property account a player's Minecraft account. The recorder adds the character that account wears at that moment.
     * @property character a SneakyCharacterManager character id, for a character that is not (known to be) worn by an
     * online player right now, such as an offline plot member.
     * @property name a fixed, non-player actor: `weather`, `server`, `npc:banker`.
     */
    data class Subject(val account: UUID?, val character: UUID?, val name: String?)

    /** A named thing: [kind] such as `orb`, `plot` or `item`, and its [id] within that kind. */
    data class Thing(val kind: String, val id: String) {
        /** `kind:id`, the form things are stored and searched in. */
        override fun toString(): String = "$kind:$id"
    }

    /** A position in the world with uid [world]. */
    data class Place(val world: UUID, val x: Double, val y: Double, val z: Double)

    /**
     * Collects one report; [fire] sends it, once. Every method returns this builder and none throws, whatever it is
     * given. When nobody listens, [of] hands out one shared builder that ignores everything, so a report costs a few
     * calls that do nothing. Use it on the thread that made it.
     */
    class Builder internal constructor(internal val type: String, private var live: Boolean) {
        internal var start: Instant = if (live) Instant.now() else Instant.EPOCH
        internal var end: Instant? = null
        internal var actor: Subject? = null
        internal val targets = ArrayList<Subject>(0)
        internal val things = ArrayList<Thing>(0)
        internal val details = LinkedHashMap<String, Any>(0)
        internal var text: String? = null
        internal var hearers: LinkedHashSet<UUID>? = null
        internal var place: Place? = null
        private var problems: ArrayList<String>? = null

        /** When it happened, when that is not now. */
        fun time(start: Instant?): Builder {
            if (live && start != null) this.start = start
            return this
        }

        /** When it ended, for something with a duration. */
        fun until(end: Instant?): Builder {
            if (live) this.end = end
            return this
        }

        /** The player who did it. The recorder adds the character they wear. */
        fun actor(player: Player?): Builder = actorAccount(player?.uniqueId)

        /** As [actor], by account: for code that has no `Player` at hand, such as the async chat thread. */
        fun actorAccount(account: UUID?): Builder {
            if (live && account != null) actor = Subject(account, null, null)
            return this
        }

        /** The character who did it, by SneakyCharacterManager character id, when no online player is at hand. */
        fun actorCharacter(character: UUID?): Builder {
            if (live && character != null) actor = Subject(null, character, null)
            return this
        }

        /** A fixed, non-player actor (`weather`, `server`, `npc:banker`): lower case, at most [MAX_NAME] characters. */
        fun actorNamed(name: String?): Builder {
            if (live) named(name)?.let { actor = it }
            return this
        }

        /** A player it was done to. Call once per target. */
        fun target(player: Player?): Builder = targetAccount(player?.uniqueId)

        /** As [target], by account. */
        fun targetAccount(account: UUID?): Builder {
            if (live && account != null) addTarget(Subject(account, null, null))
            return this
        }

        /** A character it was done to, by SneakyCharacterManager character id (an offline plot member, say). */
        fun targetCharacter(character: UUID?): Builder {
            if (live && character != null) addTarget(Subject(null, character, null))
            return this
        }

        /** A fixed, non-player target, named like [actorNamed]. */
        fun targetNamed(name: String?): Builder {
            if (live) named(name)?.let { addTarget(it) }
            return this
        }

        /**
         * A named thing this is about. [kind] is lower case (`orb`, `plot`, `item`); [id] is the thing's own stable
         * id, at most [MAX_THING_ID] characters. Use the same kind and id every time, so the thing's history is one
         * search.
         */
        fun thing(kind: String?, id: String?): Builder {
            if (!live) return this
            val trimmed = id?.trim().orEmpty()
            when {
                kind == null || !KEY.matches(kind) || kind.length > MAX_KEY -> problem("thing kind '$kind' is not a lower-case id")
                trimmed.isEmpty() || trimmed.length > MAX_THING_ID -> problem("thing '$kind' has an empty or too long id")
                things.size >= MAX_ENTRIES -> problem("more than $MAX_ENTRIES things")
                else -> Thing(kind, trimmed).let { if (it !in things) things += it }
            }
            return this
        }

        fun thing(kind: String?, id: Long): Builder = if (live) thing(kind, id.toString()) else this

        fun thing(kind: String?, id: Int): Builder = if (live) thing(kind, id.toString()) else this

        /** An extra fact. [key] is lower case; a null [value] is left out and a long one is cut at [MAX_DETAIL] characters. */
        fun detail(key: String?, value: String?): Builder {
            if (live && value != null) putDetail(key, if (value.length > MAX_DETAIL) value.substring(0, MAX_DETAIL) else value)
            return this
        }

        fun detail(key: String?, value: Long): Builder {
            if (live) putDetail(key, value)
            return this
        }

        fun detail(key: String?, value: Int): Builder {
            if (live) putDetail(key, value.toLong())
            return this
        }

        /** A number that is not finite (NaN, infinity) is left out. */
        fun detail(key: String?, value: Double): Builder {
            if (!live) return this
            if (value.isFinite()) putDetail(key, value) else problem("detail '$key' is not a finite number")
            return this
        }

        fun detail(key: String?, value: Boolean): Builder {
            if (live) putDetail(key, value)
            return this
        }

        /** What the player said or typed. Blank text counts as none; text is cut at [MAX_TEXT] characters. */
        fun text(text: String?): Builder {
            if (!live) return this
            this.text = text?.takeUnless { it.isBlank() }?.let { if (it.length > MAX_TEXT) it.substring(0, MAX_TEXT) else it }
            return this
        }

        /** The accounts that heard or saw the text. Calling it at all, even with nobody, says the reporter knows who did. */
        fun heardBy(accounts: Collection<UUID?>?): Builder {
            if (!live) return this
            val set = hearers ?: LinkedHashSet<UUID>().also { hearers = it }
            accounts?.forEach { if (it != null) addHearer(set, it) }
            return this
        }

        /** One more account that heard or saw the text. */
        fun heardBy(account: UUID?): Builder {
            if (!live) return this
            val set = hearers ?: LinkedHashSet<UUID>().also { hearers = it }
            if (account != null) addHearer(set, account)
            return this
        }

        /** Where it happened, when that is not where the actor stands. A location without a loaded world is left out. */
        fun at(location: Location?): Builder {
            if (!live || location == null) return this
            val world = if (location.isWorldLoaded) location.world?.uid else null
            return if (world == null) this else at(world, location.x, location.y, location.z)
        }

        /** As [at], for code that holds a position but no `Location`. */
        fun at(world: UUID?, x: Double, y: Double, z: Double): Builder {
            if (!live || world == null) return this
            if (x.isFinite() && y.isFinite() && z.isFinite()) place = Place(world, x, y, z) else problem("the position is not finite")
            return this
        }

        /** Reports the event to whoever listens. A builder reports once; a second call does nothing. */
        fun fire() {
            if (!live) return
            live = false
            try {
                if (!TYPE.matches(type) || type.length > MAX_TYPE) {
                    warn("dropped: the type must look like 'plugin.what_happened' (lower case, at most $MAX_TYPE characters)")
                    return
                }
                end?.let { if (it.isBefore(start)) { problem("the end is before the start"); end = null } }
                problems?.forEach { warn("left out: $it") }
                Bukkit.getPluginManager().callEvent(ChronicleEvent(this, !Bukkit.isPrimaryThread()))
            } catch (t: Throwable) {
                // A reporter must never fail because recording does.
                warn("failed: $t", t)
            }
        }

        private fun named(name: String?): Subject? {
            if (name != null && NAME.matches(name) && name.length <= MAX_NAME) return Subject(null, null, name)
            problem("the name '$name' is not a lower-case id")
            return null
        }

        private fun addTarget(target: Subject) {
            when {
                target in targets -> Unit
                targets.size >= MAX_ENTRIES -> problem("more than $MAX_ENTRIES targets")
                else -> targets += target
            }
        }

        private fun putDetail(key: String?, value: Any) {
            when {
                key == null || !KEY.matches(key) || key.length > MAX_KEY -> problem("detail key '$key' is not a lower-case id")
                details.size >= MAX_ENTRIES && key !in details -> problem("more than $MAX_ENTRIES details")
                else -> details[key] = value
            }
        }

        private fun addHearer(set: LinkedHashSet<UUID>, account: UUID) {
            if (set.size < MAX_HEARERS) set += account else if (account !in set) problem("more than $MAX_HEARERS hearers")
        }

        private fun problem(text: String) {
            val list = problems ?: ArrayList<String>(1).also { problems = it }
            if (text !in list) list += text
        }

        /** One line per distinct message and type for as long as the server runs, so a hot path can't flood the log. */
        private fun warn(message: String, cause: Throwable? = null) {
            if (WARNED.size >= MAX_WARNINGS || !WARNED.add("$type $message")) return
            Bukkit.getLogger().log(Level.WARNING, "[SneakyUtils] ChronicleEvent '$type' $message", cause)
        }
    }

    companion object {
        /** Longest [type], in characters. */
        const val MAX_TYPE = 96

        /** Longest thing kind and detail key. */
        const val MAX_KEY = 48

        /** Longest thing id. */
        const val MAX_THING_ID = 128

        /** Longest name of a named actor or target. */
        const val MAX_NAME = 64

        /** Longest text detail value; longer ones are cut. */
        const val MAX_DETAIL = 1024

        /** Longest [text]; longer text is cut. */
        const val MAX_TEXT = 8192

        /** Most targets, things and details on one event each. */
        const val MAX_ENTRIES = 32

        /** Most hearers on one event. */
        const val MAX_HEARERS = 1024

        private const val MAX_WARNINGS = 256
        private val TYPE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        private val KEY = Regex("[a-z][a-z0-9_]*")
        private val NAME = Regex("[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*")
        private val WARNED: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val HANDLERS = HandlerList()
        private val IGNORED = Builder("", false)

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        /**
         * Starts a report of [type], `<owner>.<what_happened>` in lower case with underscores, where the owner is the
         * reporting plugin's id or a shared subject such as `chat` or `voice`. The id is a contract: readers match on
         * it, so never rename one. Finish with [Builder.fire].
         */
        @JvmStatic
        fun of(type: String?): Builder = if (type == null || !hasListeners()) IGNORED else Builder(type, true)

        /** Whether anything records events right now. Only needed to skip costly preparation of a report. */
        @JvmStatic
        fun hasListeners(): Boolean = HANDLERS.registeredListeners.isNotEmpty()
    }
}
