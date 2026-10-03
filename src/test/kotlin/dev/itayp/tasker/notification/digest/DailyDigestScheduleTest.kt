package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.notification.digest.DailyDigestSchedule.Decision
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class DailyDigestScheduleTest {

    private val zone = ZoneId.of("Asia/Jerusalem") // UTC+3 in October
    private val daily8am = "0 0 8 * * *"

    private fun decide(lastRunAt: String?, now: String, cron: String = daily8am) =
        DailyDigestSchedule.decide(cron, zone, lastRunAt?.let(Instant::parse), Instant.parse(now))

    @Test
    fun `no watermark initializes instead of firing`() {
        assertEquals(Decision.INITIALIZE, decide(lastRunAt = null, now = "2026-10-05T05:01:00Z"))
    }

    @Test
    fun `not due before the local fire time`() {
        // 07:59 local
        assertEquals(Decision.NOT_DUE, decide("2026-10-04T05:10:00Z", now = "2026-10-05T04:59:00Z"))
    }

    @Test
    fun `due once the local fire time has passed`() {
        // 08:04 local, last run yesterday after 08:00
        assertEquals(Decision.DUE, decide("2026-10-04T05:10:00Z", now = "2026-10-05T05:04:00Z"))
    }

    @Test
    fun `not due again the same day after running`() {
        assertEquals(Decision.NOT_DUE, decide("2026-10-05T05:04:00Z", now = "2026-10-05T12:00:00Z"))
    }

    @Test
    fun `a fire time older than the stale window is skipped`() {
        // 11:30 local — 3.5h after 08:00
        assertEquals(Decision.STALE, decide("2026-10-04T05:10:00Z", now = "2026-10-05T08:30:00Z"))
    }

    @Test
    fun `after days of downtime the latest fire time counts, not the oldest missed one`() {
        // Last run three days ago; now 08:10 local today.
        assertEquals(Decision.DUE, decide("2026-10-02T05:10:00Z", now = "2026-10-05T05:10:00Z"))
    }

    @Test
    fun `days outside the cron are not due`() {
        // 2026-10-03 is a Saturday; weekdays only.
        assertEquals(
            Decision.NOT_DUE,
            decide("2026-10-02T05:10:00Z", now = "2026-10-03T05:10:00Z", cron = "0 0 8 * * MON-FRI"),
        )
    }
}
