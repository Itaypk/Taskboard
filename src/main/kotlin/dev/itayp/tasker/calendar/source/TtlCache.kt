package dev.itayp.tasker.calendar.source

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A small in-memory cache with a per-cache time to live, for parsed calendars: per user calendar
 * for a few minutes (#347), per holiday region for about a day (#413). Bounded by [maxEntries];
 * when full, expired entries go first, then the oldest.
 *
 * Two callers missing the same key at once both load it. That's acceptable here (a duplicate
 * fetch), and it keeps a slow fetch from blocking unrelated keys.
 */
class TtlCache<K : Any, V : Any>(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val clock: Clock,
) {
    private data class Entry<V>(val value: V, val storedAt: Instant)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        if (entries.size >= maxEntries && !entries.containsKey(key)) evict()
        entries[key] = Entry(value, clock.instant())
    }

    fun getOrLoad(key: K, loader: () -> V): V = get(key) ?: loader().also { put(key, it) }

    fun invalidate(key: K) {
        entries.remove(key)
    }

    val size: Int get() = entries.size

    private fun isExpired(entry: Entry<V>) = !clock.instant().isBefore(entry.storedAt.plus(ttl))

    private fun evict() {
        entries.entries.removeIf { isExpired(it.value) }
        while (entries.size >= maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.storedAt } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }
}
