package com.sneakyutils.triggers.engine

/**
 * Spreads members over the phases of a timer interval: [add] puts a member in the least-loaded bucket.
 *
 * An interval of `I` ticks has `B = min(I, maxBuckets)` buckets. Bucket `b` fires at phase [phaseOf]`(b) =
 * floor(b * I / B)`, so every bucket fires exactly once per interval. When `I <= maxBuckets` there is one bucket per
 * tick; for longer intervals the buckets are spread evenly over the interval (memory stays bounded at 72,000 ticks).
 *
 * [add] is amortized O(1): [minLoad] is always the smallest bucket load, [atMin] counts the buckets at that load, and
 * a cursor walks forward to the next bucket at the minimum. Ties go to the next bucket after the cursor, so a fresh
 * balancer fills buckets 0, 1, 2, ... in order.
 */
internal class BucketBalancer(val interval: Int, maxBuckets: Int = DEFAULT_MAX_BUCKETS) {
    val buckets: Int
    private val counts: IntArray
    private var minLoad = 0
    private var atMin: Int
    private var cursor = 0

    /** Total members. */
    var total: Int = 0
        private set

    init {
        require(interval >= 1) { "interval must be at least 1, got $interval" }
        require(maxBuckets >= 1) { "maxBuckets must be at least 1" }
        buckets = minOf(interval, maxBuckets)
        counts = IntArray(buckets)
        atMin = buckets
    }

    fun load(bucket: Int): Int = counts[bucket]

    /** The smallest bucket load. */
    val minimumLoad: Int get() = minLoad

    /** Adds a member to a least-loaded bucket and returns the bucket. */
    fun add(): Int {
        var b = cursor
        while (counts[b] != minLoad) b = if (b + 1 == buckets) 0 else b + 1
        counts[b]++
        total++
        cursor = if (b + 1 == buckets) 0 else b + 1
        atMin--
        if (atMin == 0) {
            // Every bucket is now above the old minimum, so the new minimum is one higher.
            minLoad++
            var n = 0
            for (c in counts) if (c == minLoad) n++
            atMin = n
        }
        return b
    }

    /** Removes a member from [bucket]. The bucket must have one. */
    fun remove(bucket: Int) {
        check(counts[bucket] > 0) { "bucket $bucket is empty" }
        counts[bucket]--
        total--
        val c = counts[bucket]
        if (c < minLoad) {
            minLoad = c
            atMin = 1
            cursor = bucket
        } else if (c == minLoad) {
            atMin++
        }
    }

    /** The phase (0 until [interval]) at which [bucket] fires. */
    fun phaseOf(bucket: Int): Int = ((bucket.toLong() * interval) / buckets).toInt()

    /** The bucket that fires at [phase] (0 until [interval]), or -1 when none does. */
    fun bucketAt(phase: Int): Int {
        if (buckets == interval) return phase
        // phaseOf(b) == phase  <=>  phase * B / I <= b < (phase + 1) * B / I; that range holds at most one integer.
        val b = ((phase.toLong() * buckets + interval - 1) / interval).toInt()
        return if (b < buckets && phaseOf(b) == phase) b else -1
    }

    companion object {
        /** Bucket cap per interval: 1,024 buckets cover intervals up to ~51 s one tick apart. */
        const val DEFAULT_MAX_BUCKETS = 1024

        const val MAX_INTERVAL = 72_000
    }
}
