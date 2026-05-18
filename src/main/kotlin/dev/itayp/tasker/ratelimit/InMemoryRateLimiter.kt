package dev.itayp.tasker.ratelimit

import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window log rate limiter backed by an in-memory ConcurrentHashMap.
 *
 * Good enough for a single-instance deployment. Swap for a Redis-backed
 * implementation (same RateLimiter interface) when running multiple replicas
 * or when limits need to survive a restart.
 */
class InMemoryRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
) : RateLimiter {

    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun tryConsume(key: String): Boolean {
        val deque = buckets.computeIfAbsent(key) { ArrayDeque() }
        val now = System.currentTimeMillis()
        val cutoff = now - windowMillis
        // Synchronise on the per-key deque; different keys never contend.
        synchronized(deque) {
            while (deque.isNotEmpty() && deque.first() <= cutoff) deque.removeFirst()
            if (deque.size < limit) {
                deque.addLast(now)
                return true
            }
            return false
        }
    }
}
