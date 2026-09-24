package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TelegramReachabilityServiceTest {

    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-24T10:00:00Z")
    private val userRepository: UserRepository = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private val service = TelegramReachabilityService(userRepository, eventPublisher, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `becoming reachable reschedules planning so the skipped cron registers`() {
        whenever(userRepository.stampTelegramChatReady(userId, now)).thenReturn(1)

        service.markReachable(userId)

        verify(eventPublisher).publishEvent(UserPlanningScheduleChangedEvent(userId))
    }

    @Test
    fun `an already-reachable chat does not reschedule again`() {
        whenever(userRepository.stampTelegramChatReady(userId, now)).thenReturn(0)

        service.markReachable(userId)

        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `becoming unreachable reschedules planning so the cron is dropped`() {
        whenever(userRepository.clearTelegramChatReady(userId)).thenReturn(1)

        service.markUnreachable(userId)

        verify(eventPublisher).publishEvent(UserPlanningScheduleChangedEvent(userId))
    }

    @Test
    fun `an already-unreachable chat does not reschedule again`() {
        whenever(userRepository.clearTelegramChatReady(userId)).thenReturn(0)

        service.markUnreachable(userId)

        verify(eventPublisher, never()).publishEvent(any<Any>())
    }
}
