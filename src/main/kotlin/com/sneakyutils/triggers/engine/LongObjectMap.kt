package com.sneakyutils.triggers.engine

/**
 * A hash map from primitive `long` keys to non-null values: open addressing with linear probing, a power-of-two
 * table at most half full, and backward-shift deletion (no tombstones, so lookups stay short after removals).
 *
 * [get] never allocates or boxes. [put] allocates only when the table grows. Iterate with [forEachEntry] (inline, so
 * no lambda object). Not thread-safe: the trigger engine uses it on the server thread only.
 *
 * Invariant: a slot is empty exactly when its value is null, and every stored key sits in the probe run that starts
 * at its home slot (`hash(key) and mask`) with no empty slot in between.
 */
internal class LongObjectMap<V : Any>(expectedSize: Int = 8) {
    private var keys: LongArray
    private var values: Array<Any?>
    private var mask: Int

    /** Number of entries. */
    var size: Int = 0
        private set

    init {
        require(expectedSize >= 0) { "expectedSize must not be negative" }
        val capacity = tableSizeFor(expectedSize)
        keys = LongArray(capacity)
        values = arrayOfNulls(capacity)
        mask = capacity - 1
    }

    val isEmpty: Boolean get() = size == 0

    /** Table length, for tests of the resize policy. */
    internal val capacity: Int get() = keys.size

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        val ks = keys
        val vs = values
        val m = mask
        var i = hash(key) and m
        while (true) {
            val v = vs[i] ?: return null
            if (ks[i] == key) return v as V
            i = (i + 1) and m
        }
    }

    fun containsKey(key: Long): Boolean = get(key) != null

    /** Stores [value] under [key]; returns the previous value or null. */
    @Suppress("UNCHECKED_CAST")
    fun put(key: Long, value: V): V? {
        var i = hash(key) and mask
        while (true) {
            val v = values[i]
            if (v == null) {
                keys[i] = key
                values[i] = value
                size++
                if (size * 2 > keys.size) resize(keys.size * 2)
                return null
            }
            if (keys[i] == key) {
                values[i] = value
                return v as V
            }
            i = (i + 1) and mask
        }
    }

    /** Removes [key]; returns the removed value or null. */
    @Suppress("UNCHECKED_CAST")
    fun remove(key: Long): V? {
        val m = mask
        var i = hash(key) and m
        while (true) {
            val v = values[i] ?: return null
            if (keys[i] == key) {
                shiftBack(i)
                size--
                return v as V
            }
            i = (i + 1) and m
        }
    }

    fun clear() {
        values.fill(null)
        size = 0
    }

    /** Calls [action] for every entry. The map must not be modified during the call. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEachEntry(action: (key: Long, value: V) -> Unit) {
        val ks = keysForIteration()
        val vs = valuesForIteration()
        for (i in vs.indices) {
            val v = vs[i] ?: continue
            action(ks[i], v as V)
        }
    }

    @PublishedApi
    internal fun keysForIteration(): LongArray = keys

    @PublishedApi
    internal fun valuesForIteration(): Array<Any?> = values

    /**
     * Fills the hole at [start] by moving later entries of the same probe run back, so no run is broken. An entry at
     * `j` may move into the hole at `gap` only if `gap` lies on its probe path, i.e. the cyclic distance from its home
     * slot to `j` is at least the distance from `gap` to `j`.
     */
    private fun shiftBack(start: Int) {
        val m = mask
        var gap = start
        var j = (start + 1) and m
        while (true) {
            val v = values[j] ?: break
            val home = hash(keys[j]) and m
            if (((j - home) and m) >= ((j - gap) and m)) {
                keys[gap] = keys[j]
                values[gap] = v
                gap = j
            }
            j = (j + 1) and m
        }
        values[gap] = null
    }

    private fun resize(newCapacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(newCapacity)
        values = arrayOfNulls(newCapacity)
        mask = newCapacity - 1
        for (i in oldValues.indices) {
            val v = oldValues[i] ?: continue
            var j = hash(oldKeys[i]) and mask
            while (values[j] != null) j = (j + 1) and mask
            keys[j] = oldKeys[i]
            values[j] = v
        }
    }

    companion object {
        private const val MAX_CAPACITY = 1 shl 30

        /** Spreads all 64 key bits into the low bits (chunk keys differ mostly in high bits). */
        @JvmStatic
        fun hash(key: Long): Int {
            var h = key * -0x61c8864680b583ebL // 0x9E3779B97F4A7C15, the 64-bit golden ratio
            h = h xor (h ushr 32)
            return (h xor (h ushr 16)).toInt()
        }

        private fun tableSizeFor(expected: Int): Int {
            var cap = 8
            while (cap < MAX_CAPACITY && cap < expected * 2) cap = cap shl 1
            return cap
        }
    }
}
