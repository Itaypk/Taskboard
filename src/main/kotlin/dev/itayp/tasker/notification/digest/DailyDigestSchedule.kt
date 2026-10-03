package dev.itayp.tasker.notification.digest

import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Decides, on each scheduler tick, whether a user's daily digest is due. Pure: the watermark
 * (`user_settings.daily_digest_last_run_at`) and the clock come in, a [Decision] comes out.
 */
object DailyDigestSchedule {

    /** A digest whose fire time is older than this is skipped rather than sent late. */
    val STALE_AFTER: Duration = Duration.ofHours(3)

    /** Guards the catch-up loop; a daily cron needs one step per missed day. */
    private const val MAX_STEPS = 400

    enum class Decision {
        /** No watermark yet: set it and wait for the next fire time, so nothing fires on deploy. */
        INITIALIZE,

        /** The next fire time hasn't come yet. */
        NOT_DUE,

        /** A fire time has passed recently enough: advance the watermark and send. */
        DUE,

        /** The latest fire time passed too long ago (e.g. downtime): advance the watermark only. */
        STALE,
    }

    fun decide(cron: String, zone: ZoneId, lastRunAt: Instant?, now: Instant): Decision {
        if (lastRunAt == null) return Decision.INITIALIZE
        val expression = CronExpression.parse(cron)
        // Walk to the *latest* fire time not after now, so a long gap doesn't make today's digest
        // look stale just because an older one was missed.
        var latest: Instant? = null
        var next = expression.next(lastRunAt.atZone(zone))
        var steps = 0
        while (next != null && !next.toInstant().isAfter(now) && steps++ < MAX_STEPS) {
            latest = next.toInstant()
            next = expression.next(next)
        }
        val fireAt = latest ?: return Decision.NOT_DUE
        return if (Duration.between(fireAt, now) > STALE_AFTER) Decision.STALE else Decision.DUE
    }
}
