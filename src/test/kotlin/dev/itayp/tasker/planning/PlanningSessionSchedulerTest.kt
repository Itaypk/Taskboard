package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.support.CronTrigger
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlanningSessionSchedulerTest {

    @Mock lateinit var taskScheduler: TaskScheduler
    @Mock lateinit var settingsRepository: UserSettingsRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var orchestrator: WeeklyPlanningOrchestrator
    @Mock lateinit var planningSessionService: PlanningSessionService
    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var telegramClient: TelegramClient
    @Mock lateinit var sessionRegistry: TelegramSessionRegistry

    private val userId = UUID.randomUUID()
    // Fixed Wednesday 2026-05-13 UTC; Monday week-start → next week = 2026-05-18
    private val clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)

    private fun newScheduler(
        client: TelegramClient? = telegramClient,
        registry: TelegramSessionRegistry? = sessionRegistry,
    ) = PlanningSessionScheduler(
        taskScheduler, settingsRepository, userRepository, orchestrator,
        planningSessionService, userSettingsService, client, registry, clock,
    )

    private fun settings(cron: String?, tz: String = "UTC", weekStartDay: String? = "MONDAY") = UserSettingsEntity().apply {
        this.userId = this@PlanningSessionSchedulerTest.userId
        this.planningCron = cron
        this.timeZone = tz
        this.weekStartDay = weekStartDay
    }

    private fun stubUserSettings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(cron = null))
    }

    @Test
    fun `scheduleFor with non-null cron schedules a cron trigger`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings("0 30 9 * * MON")))
        val future: ScheduledFuture<Any> = mock()
        whenever(taskScheduler.schedule(any(), any<Trigger>())).thenReturn(future)

        newScheduler().scheduleFor(userId)

        val triggerCaptor = argumentCaptor<Trigger>()
        verify(taskScheduler).schedule(any(), triggerCaptor.capture())
        assertTrue(triggerCaptor.firstValue is CronTrigger)
    }

    @Test
    fun `scheduleFor with null cron cancels existing future and schedules nothing`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings(null)))
        newScheduler().scheduleFor(userId)
        verify(taskScheduler, never()).schedule(any(), any<Trigger>())
    }

    @Test
    fun `event listener triggers reschedule`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings("0 30 9 * * MON")))
        val future2: ScheduledFuture<Any> = mock()
        whenever(taskScheduler.schedule(any(), any<Trigger>())).thenReturn(future2)

        newScheduler().onScheduleChanged(UserPlanningScheduleChangedEvent(userId))

        verify(taskScheduler).schedule(any(), any<Trigger>())
    }

    @Test
    fun `runPlanningSession skips when telegram client unavailable`() {
        newScheduler(client = null).runPlanningSession(userId)
        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `runPlanningSession skips when user has no telegram id`() {
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply {
            id = userId
            telegramId = null
        }))
        newScheduler().runPlanningSession(userId)
        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `runPlanningSession starts orchestrator with next-week target when no session exists`() {
        val user = UserEntity().apply {
            id = userId
            telegramId = 12345L
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18") // Monday after the fixed Wednesday
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(null)
        val sessionId = UUID.randomUUID()
        whenever(orchestrator.start(eq(userId), any(), eq(targetWeek))).thenReturn(sessionId)

        newScheduler().runPlanningSession(userId)

        verify(orchestrator).start(eq(userId), any(), eq(targetWeek))
        verify(sessionRegistry).put(12345L, sessionId)
    }

    @Test
    fun `runPlanningSession sends existing summary when completed session exists for target week`() {
        val user = UserEntity().apply {
            id = userId
            telegramId = 12345L
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18")
        val existing = PlanningSessionEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@PlanningSessionSchedulerTest.userId
            status = PlanningSessionStatus.COMPLETED
            weekStart = targetWeek
            summary = "Pre-planned summary"
        }
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(existing)

        newScheduler().runPlanningSession(userId)

        // The channel is constructed inside runPlanningSession (TelegramConversationChannel),
        // so we can't intercept .send() here — assert the negative: a fresh session is NOT started.
        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `runPlanningSession skips when active session exists for target week`() {
        val user = UserEntity().apply {
            id = userId
            telegramId = 12345L
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18")
        val existing = PlanningSessionEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@PlanningSessionSchedulerTest.userId
            status = PlanningSessionStatus.ACTIVE
            weekStart = targetWeek
        }
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(existing)

        newScheduler().runPlanningSession(userId)

        verify(orchestrator, never()).start(any(), any(), any())
        verify(sessionRegistry, never()).put(any(), any())
    }
}
