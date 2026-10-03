package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.notification.digest.DailyDigestSchedule.Decision
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Single-instance poller for daily digests (docs/DAILY-DIGEST.md, "Delivery"). Like the slot-reminder
 * poller it keeps no in-memory schedule: each tick re-reads the users with the digest enabled and
 * compares their cron against a DB watermark, so restarts and cron/time-zone edits need no
 * re-registration.
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

    @Scheduled(fixedDelay = POLL_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
    fun poll() {
        val now = clock.instant()
        for (settings in userSettingsService.findAllWithDailyDigest()) {
            try {
                runFor(settings, now)
            } catch (e: Exception) {
                log.error("Daily digest run failed for user {}", settings.userId, e)
            }
        }
    }

    internal fun runFor(settings: UserSettingsEntity, now: Instant) {
        val userId = settings.userId ?: return
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

    companion object {
        private const val POLL_INTERVAL_MINUTES = 5L
    }
}
