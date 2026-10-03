package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor

class DailyDigestSchedulerTest {

    private val userSettingsService: UserSettingsService = mock()
    private val digestService: DailyDigestService = mock()
    private val now = Instant.parse("2026-10-05T08:02:00Z")
    private val scheduler = DailyDigestScheduler(userSettingsService, digestService, Clock.fixed(now, ZoneOffset.UTC))
        .apply { executor = Executor { it.run() } }

    private val userId = UUID.randomUUID()

    private fun settings(lastRunAt: Instant?, enabled: Boolean = true) = UserSettingsEntity().apply {
        this.userId = this@DailyDigestSchedulerTest.userId
        timeZone = "UTC"
        dailyDigestEnabled = enabled
        dailyDigestCron = "0 0 8 * * *"
        dailyDigestLastRunAt = lastRunAt
    }

    private fun given(row: UserSettingsEntity) {
        whenever(userSettingsService.findAllWithDailyDigest()).thenReturn(listOf(row))
        whenever(userSettingsService.findDailyDigestSchedule(userId)).thenReturn(row)
    }

    @Test
    fun `a due digest advances the watermark, then sends`() {
        given(settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z")))

        scheduler.poll()

        val order = inOrder(userSettingsService, digestService)
        order.verify(userSettingsService).markDailyDigestRun(userId, now)
        order.verify(digestService).send(userId, now)
    }

    @Test
    fun `the first sighting only sets the watermark`() {
        given(settings(lastRunAt = null))

        scheduler.poll()

        verify(userSettingsService).markDailyDigestRun(userId, now)
        verify(digestService, never()).send(any(), any())
    }

    @Test
    fun `a run re-reads the row, so a digest disabled meanwhile isn't sent`() {
        whenever(userSettingsService.findAllWithDailyDigest())
            .thenReturn(listOf(settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z"))))
        whenever(userSettingsService.findDailyDigestSchedule(userId))
            .thenReturn(settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z"), enabled = false))

        scheduler.poll()

        verify(digestService, never()).send(any(), any())
    }

    @Test
    fun `a user still in flight is not dispatched again`() {
        given(settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z")))
        val queued = mutableListOf<Runnable>()
        scheduler.executor = Executor { queued += it }

        scheduler.poll()
        scheduler.poll()
        assert(queued.size == 1) { "expected one queued run, got ${queued.size}" }

        // Once the run completes, the next tick may dispatch the user again.
        queued.single().run()
        scheduler.poll()
        assert(queued.size == 2) { "expected a second queued run, got ${queued.size}" }
    }

    @Test
    fun `one user's failure doesn't stop the others`() {
        val other = UUID.randomUUID()
        val otherRow = settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z")).apply { userId = other }
        whenever(userSettingsService.findAllWithDailyDigest())
            .thenReturn(listOf(settings(lastRunAt = Instant.parse("2026-10-04T08:01:00Z")), otherRow))
        whenever(userSettingsService.findDailyDigestSchedule(userId)).thenThrow(RuntimeException("boom"))
        whenever(userSettingsService.findDailyDigestSchedule(other)).thenReturn(otherRow)

        scheduler.poll()

        verify(digestService, times(1)).send(other, now)
    }
}
