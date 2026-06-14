package dev.itayp.tasker.service

import dev.itayp.tasker.repository.UserRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-user activity for inactivity-based account cleanup without coupling to Spring Session
 * internals. Authenticated API requests call [touch]; we keep the latest timestamp in memory and
 * persist it to `users.last_active_at` lazily — at most once per [THROTTLE] per user. Net write
 * volume is ~1 row update per active user per hour, and a scrape/flush never blocks the request.
 *
 * State is process-local: a restart simply means the next request re-touches and re-persists. The
 * maps are bounded by the active-user set seen during this process's lifetime.
 */
@Component
class ActivityTracker(
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ActivityTracker::class.java)

    // userId -> latest activity instant not yet persisted.
    private val pending = ConcurrentHashMap<UUID, java.time.Instant>()

    // userId -> last instant we persisted, used to throttle writes.
    private val lastPersisted = ConcurrentHashMap<UUID, java.time.Instant>()

    fun touch(userId: UUID) {
        val now = clock.instant()
        val persisted = lastPersisted[userId]
        if (persisted == null || Duration.between(persisted, now) >= THROTTLE) {
            pending[userId] = now
        }
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MILLIS)
    @Transactional
    fun flush() {
        if (pending.isEmpty()) return
        // Snapshot-and-clear: drain each key individually so touches arriving mid-flush aren't lost.
        val drained = pending.keys.toList()
        var written = 0
        for (userId in drained) {
            val ts = pending.remove(userId) ?: continue
            try {
                userRepository.touchLastActiveAt(userId, ts)
                lastPersisted[userId] = ts
                written++
            } catch (e: Exception) {
                // Re-queue so a transient failure doesn't drop the activity signal.
                pending.putIfAbsent(userId, ts)
                log.warn("Failed to persist last_active_at for user {}", userId, e)
            }
        }
        if (written > 0) log.debug("Flushed last_active_at for {} user(s)", written)
    }

    @PreDestroy
    fun flushOnShutdown() {
        try {
            flush()
        } catch (e: Exception) {
            log.warn("Final activity flush on shutdown failed", e)
        }
    }

    companion object {
        private val THROTTLE: Duration = Duration.ofHours(1)
        private const val FLUSH_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
