package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.UserSettings
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
    @Mock lateinit var orchestrator: WeeklyPlanningOrchestrator
    @Mock lateinit var planningSessionService: PlanningSessionService
    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var channelResolver: ScheduledConversationChannelResolver

    private val userId = UUID.randomUUID()
    // Fixed Wednesday 2026-05-13 UTC; Monday week-start → next week = 2026-05-18
    private val clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)

    private val scheduler by lazy {
        PlanningSessionScheduler(
            taskScheduler, settingsRepository, orchestrator,
            planningSessionService, userSettingsService, channelResolver, clock,
        )
    }

    private fun settings(cron: String?, tz: String = "UTC", weekStartDay: String? = "MONDAY") = UserSettingsEntity().apply {
        this.userId = this@PlanningSessionSchedulerTest.userId
        this.planningCron = cron
        this.timeZone = tz
        this.weekStartDay = weekStartDay
    }

    private fun stubUserSettings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(domainSettings(cron = null))
    }

    private fun domainSettings(cron: String?, tz: String = "UTC", weekStartDay: String? = "MONDAY") =
        UserSettings(
            userId = userId,
            displayName = null,
            contextBlock = null,
            timeZone = tz,
            preferredLanguage = "en-US",
            calendarInviteEmail = false,
            gender = null,
            agentDescription = null,
            planningCron = cron,
            weekStartDay = weekStartDay,
            autoArchiveDays = null,
        )

    private fun resolved(started: MutableList<UUID> = mutableListOf()): ScheduledConversationChannelResolver.Resolved =
        ScheduledConversationChannelResolver.Resolved(mock<ConversationChannel>()) { started.add(it) }

    @Test
    fun `scheduleFor with cron and deliverable channel schedules a cron trigger`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings("0 30 9 * * MON")))
        whenever(channelResolver.hasDeliverableChannel(userId)).thenReturn(true)
        val future: ScheduledFuture<Any> = mock()
        whenever(taskScheduler.schedule(any(), any<Trigger>())).thenReturn(future)

        scheduler.scheduleFor(userId)

        val triggerCaptor = argumentCaptor<Trigger>()
        verify(taskScheduler).schedule(any(), triggerCaptor.capture())
        assertTrue(triggerCaptor.firstValue is CronTrigger)
    }

    @Test
    fun `scheduleFor skips scheduling when the user has no deliverable channel`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings("0 30 9 * * MON")))
        whenever(channelResolver.hasDeliverableChannel(userId)).thenReturn(false)

        scheduler.scheduleFor(userId)

        verify(taskScheduler, never()).schedule(any(), any<Trigger>())
    }

    @Test
    fun `scheduleFor with null cron cancels existing future and schedules nothing`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings(null)))
        scheduler.scheduleFor(userId)
        verify(taskScheduler, never()).schedule(any(), any<Trigger>())
    }

    @Test
    fun `event listener triggers reschedule`() {
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings("0 30 9 * * MON")))
        whenever(channelResolver.hasDeliverableChannel(userId)).thenReturn(true)
        val future2: ScheduledFuture<Any> = mock()
        whenever(taskScheduler.schedule(any(), any<Trigger>())).thenReturn(future2)

        scheduler.onScheduleChanged(UserPlanningScheduleChangedEvent(userId))

        verify(taskScheduler).schedule(any(), any<Trigger>())
    }

    @Test
    fun `runPlanningSession skips when no deliverable channel resolves`() {
        whenever(channelResolver.resolve(userId)).thenReturn(null)
        scheduler.runPlanningSession(userId)
        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `runPlanningSession starts orchestrator with next-week target when no session exists`() {
        val started = mutableListOf<UUID>()
        whenever(channelResolver.resolve(userId)).thenReturn(resolved(started))
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18") // Monday after the fixed Wednesday
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(null)
        val sessionId = UUID.randomUUID()
        whenever(orchestrator.start(eq(userId), any(), eq(targetWeek))).thenReturn(sessionId)

        scheduler.runPlanningSession(userId)

        verify(orchestrator).start(eq(userId), any(), eq(targetWeek))
        assertTrue(started.contains(sessionId), "started session should be recorded against the channel")
    }

    @Test
    fun `runPlanningSession sends existing summary when completed session exists for target week`() {
        val resolvedChannel = resolved()
        whenever(channelResolver.resolve(userId)).thenReturn(resolvedChannel)
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18")
        val existing = PlanningSession(
            id = UUID.randomUUID(),
            userId = userId,
            conversationId = null,
            status = PlanningSessionStatus.COMPLETED,
            startedAt = Instant.parse("2026-05-18T08:00:00Z"),
            weekStart = targetWeek,
            endedAt = null,
            summary = "Pre-planned summary",
        )
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(existing)

        scheduler.runPlanningSession(userId)

        verify(orchestrator, never()).start(any(), any(), any())
        verify(resolvedChannel.channel).send(any())
    }

    @Test
    fun `runPlanningSession skips when active session exists for target week`() {
        val started = mutableListOf<UUID>()
        whenever(channelResolver.resolve(userId)).thenReturn(resolved(started))
        stubUserSettings()
        val targetWeek = LocalDate.parse("2026-05-18")
        val existing = PlanningSession(
            id = UUID.randomUUID(),
            userId = userId,
            conversationId = null,
            status = PlanningSessionStatus.ACTIVE,
            startedAt = Instant.parse("2026-05-18T08:00:00Z"),
            weekStart = targetWeek,
            endedAt = null,
            summary = null,
        )
        whenever(planningSessionService.findSessionForWeek(userId, targetWeek)).thenReturn(existing)

        scheduler.runPlanningSession(userId)

        verify(orchestrator, never()).start(any(), any(), any())
        assertTrue(started.isEmpty(), "no session should be recorded when an active session already exists")
    }
}
