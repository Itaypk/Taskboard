package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.support.CronTrigger
import org.telegram.telegrambots.meta.generics.TelegramClient
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
    @Mock lateinit var telegramClient: TelegramClient
    @Mock lateinit var sessionRegistry: TelegramSessionRegistry

    private val userId = UUID.randomUUID()

    private fun newScheduler(
        client: TelegramClient? = telegramClient,
        registry: TelegramSessionRegistry? = sessionRegistry,
    ) = PlanningSessionScheduler(
        taskScheduler, settingsRepository, userRepository, orchestrator, client, registry,
    )

    private fun settings(cron: String?, tz: String = "UTC") = UserSettingsEntity().apply {
        this.userId = this@PlanningSessionSchedulerTest.userId
        this.planningCron = cron
        this.timeZone = tz
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
        verify(orchestrator, never()).start(any(), any())
    }

    @Test
    fun `runPlanningSession skips when user has no telegram id`() {
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply {
            id = userId
            telegramId = null
        }))
        newScheduler().runPlanningSession(userId)
        verify(orchestrator, never()).start(any(), any())
    }

    @Test
    fun `runPlanningSession starts orchestrator and registers session when telegram id present`() {
        val user = UserEntity().apply {
            id = userId
            telegramId = 12345L
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        val sessionId = UUID.randomUUID()
        whenever(orchestrator.start(any(), any())).thenReturn(sessionId)

        newScheduler().runPlanningSession(userId)

        verify(orchestrator).start(any(), any())
        verify(sessionRegistry).put(12345L, sessionId)
    }
}
