package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.notification.digest.DailyDigestSchedule.Decision
import dev.itayp.tasker.service.UserSettingsService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Single-instance poller for daily digests (docs/DAILY-DIGEST.md, "Delivery"). Like the slot-reminder
 * poller it keeps no in-memory schedule: each tick re-reads the users with the digest enabled and
 * compares their cron against a DB watermark, so restarts and cron/time-zone edits need no
 * re-registration.
 *
 * The tick itself only dispatches. Each user's run (compose + Telegram send) happens on a small
 * pool of its own, so a slow batch never holds one of the two threads every `@Scheduled` job in the
 * app shares. A user whose run is still in flight is skipped by the next tick, and the run re-reads
 * the watermark when it starts, so a run can't be dispatched twice off a stale read.
 *
 * The watermark is committed *before* the send: a crash mid-send loses that day's digest rather
 * than sending it twice, which is the right trade for a daily summary.
 */
@Component
class DailyDigestScheduler(
    private val userSettingsService: UserSettingsService,
    private val digestService: DailyDigestService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DailyDigestScheduler::class.java)

    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Deliberately not a Spring bean: a user-defined `Executor` bean can make Boot's
     * auto-configured `applicationTaskExecutor` back off, which would move every `@Async` method
     * onto this pool.
     */
    private val pool = ThreadPoolTaskExecutor().apply {
        corePoolSize = POOL_SIZE
        maxPoolSize = POOL_SIZE
        queueCapacity = QUEUE_CAPACITY
        setThreadNamePrefix("daily-digest-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
        initialize()
    }

    /** Where runs execute; tests swap in a same-thread executor. */
    internal var executor: Executor = pool

    @Scheduled(fixedDelay = POLL_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
    fun poll() {
        for (settings in userSettingsService.findAllWithDailyDigest()) {
            val userId = settings.userId ?: continue
            if (!inFlight.add(userId)) continue
            try {
                executor.execute {
                    try {
                        runFor(userId)
                    } catch (e: Exception) {
                        log.error("Daily digest run failed for user {}", userId, e)
                    } finally {
                        inFlight.remove(userId)
                    }
                }
            } catch (e: TaskRejectedException) {
                // Queue full: leave the user for the next tick rather than block the scheduler.
                inFlight.remove(userId)
                log.warn("Daily digest queue full; deferring user {} to the next tick", userId)
            }
        }
    }

    internal fun runFor(userId: UUID) {
        // Re-read rather than trust the row the tick loaded: it may predate a run that just finished.
        val settings = userSettingsService.findDailyDigestSchedule(userId) ?: return
        if (!settings.dailyDigestEnabled) return
        val now = clock.instant()
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        when (DailyDigestSchedule.decide(settings.dailyDigestCron, zone, settings.dailyDigestLastRunAt, now)) {
            Decision.NOT_DUE -> Unit
            Decision.INITIALIZE -> userSettingsService.markDailyDigestRun(userId, now)
            Decision.STALE -> {
                userSettingsService.markDailyDigestRun(userId, now)
                log.info("Skipped stale daily digest for user {}", userId)
            }
            Decision.DUE -> {
                userSettingsService.markDailyDigestRun(userId, now)
                digestService.send(userId, now)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        pool.shutdown()
    }

    companion object {
        private const val POLL_INTERVAL_MINUTES = 5L
        private const val POOL_SIZE = 2
        private const val QUEUE_CAPACITY = 500
    }
}
